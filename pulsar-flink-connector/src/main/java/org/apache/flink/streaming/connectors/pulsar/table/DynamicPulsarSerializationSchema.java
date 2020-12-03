/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.pulsar.table;

import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.pulsar.source.MessageSerializer;
import org.apache.flink.formats.avro.typeutils.AvroSchemaConverter;
import org.apache.flink.streaming.util.serialization.FlinkSchema;
import org.apache.flink.streaming.util.serialization.PulsarContextAware;
import org.apache.flink.streaming.util.serialization.PulsarSerializationSchema;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.RowType;
import org.apache.flink.types.RowKind;

import org.apache.commons.lang3.StringUtils;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.TypedMessageBuilder;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.schema.SchemaType;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.apache.flink.shaded.guava18.com.google.common.base.Preconditions.checkArgument;

/**
 * A specific {@link MessageSerializer} for {@link PulsarDynamicTableSink}.
 */
class DynamicPulsarSerializationSchema
        implements PulsarSerializationSchema<RowData>, PulsarContextAware<RowData> {

    private static final long serialVersionUID = 1L;

    private final @Nullable
    SerializationSchema<RowData> keySerialization;

    private final SerializationSchema<RowData> valueSerialization;

    private final RowData.FieldGetter[] keyFieldGetters;

    private final RowData.FieldGetter[] valueFieldGetters;

    private final boolean hasMetadata;

    private final boolean upsertMode;

    /**
     * Contains the position for each value of {@link PulsarDynamicTableSink.WritableMetadata} in the consumed row or
     * -1 if this metadata key is not used.
     */
    private final int[] metadataPositions;

    private int[] partitions;

    private int parallelInstanceId;

    private int numParallelInstances;

    private DataType valueDataType;

    private String valueFormatType;

    DynamicPulsarSerializationSchema(
            @Nullable SerializationSchema<RowData> keySerialization,
            SerializationSchema<RowData> valueSerialization,
            RowData.FieldGetter[] keyFieldGetters,
            RowData.FieldGetter[] valueFieldGetters,
            boolean hasMetadata,
            int[] metadataPositions,
            boolean upsertMode,
            DataType valueDataType,
            String valueFormatType) {
        if (upsertMode) {
            checkArgument(keySerialization != null && keyFieldGetters.length > 0,
                    "Key must be set in upsert mode for serialization schema.");
        }
        this.keySerialization = keySerialization;
        this.valueSerialization = valueSerialization;
        this.keyFieldGetters = keyFieldGetters;
        this.valueFieldGetters = valueFieldGetters;
        this.hasMetadata = hasMetadata;
        this.metadataPositions = metadataPositions;
        this.upsertMode = upsertMode;
        this.valueDataType = valueDataType;
        this.valueFormatType = valueFormatType;
    }

    @Override
    public void open(SerializationSchema.InitializationContext context) throws Exception {
        valueSerialization.open(context);
    }

    @Override
    public void serialize(RowData consumedRow, TypedMessageBuilder<RowData> messageBuilder) {

        // shortcut in case no input projection is required
        if (keySerialization == null && !hasMetadata) {
            messageBuilder.value(consumedRow);
            return;
        }

        if (keySerialization != null) {
            final RowData keyRow = createProjectedRow(consumedRow, RowKind.INSERT, keyFieldGetters);
            messageBuilder.keyBytes(keySerialization.serialize(keyRow));
        }

        final RowKind kind = consumedRow.getRowKind();
        final RowData valueRow = createProjectedRow(consumedRow, kind, valueFieldGetters);
        if (upsertMode) {
            if (kind == RowKind.DELETE || kind == RowKind.UPDATE_BEFORE) {
                // transform the message as the tombstone message
            } else {
                // make the message to be INSERT to be compliant with the INSERT-ONLY format
                valueRow.setRowKind(RowKind.INSERT);
                messageBuilder.value(valueRow);
            }
        } else {
            messageBuilder.value(valueRow);
        }

        messageBuilder.properties(readMetadata(consumedRow, PulsarDynamicTableSink.WritableMetadata.PROPERTIES));
        final Long eventTime = readMetadata(consumedRow, PulsarDynamicTableSink.WritableMetadata.EVENT_TIME);
        if (eventTime != null && eventTime >= 0) {
            messageBuilder.eventTime(eventTime);
        }
        final String key = readMetadata(consumedRow, PulsarDynamicTableSink.WritableMetadata.KEY);
        if (key != null) {
            messageBuilder.key(key);
        }
    }

    public Optional<String> getTargetTopic(RowData element) {
        //TODO need get topic from row.
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private <T> T readMetadata(RowData consumedRow, PulsarDynamicTableSink.WritableMetadata metadata) {
        final int pos = metadataPositions[metadata.ordinal()];
        if (pos < 0) {
            return null;
        }
        return (T) metadata.converter.read(consumedRow, pos);
    }

    private static RowData createProjectedRow(RowData consumedRow, RowKind kind, RowData.FieldGetter[] fieldGetters) {
        final int arity = fieldGetters.length;
        final GenericRowData genericRowData = new GenericRowData(kind, arity);
        for (int fieldPos = 0; fieldPos < arity; fieldPos++) {
            genericRowData.setField(fieldPos, fieldGetters[fieldPos].getFieldOrNull(consumedRow));
        }
        return genericRowData;
    }

    @Override
    public TypeInformation<RowData> getProducedType() {
        final RowType rowType = (RowType) valueDataType.getLogicalType();
        return InternalTypeInfo.of(rowType);
    }

    @Override
    public Schema<RowData> getSchema() {
        if (StringUtils.isBlank(valueFormatType)) {
            return new FlinkSchema<>(Schema.BYTES.getSchemaInfo(), valueSerialization, null);
        }
        switch (StringUtils.lowerCase(valueFormatType)) {
            case "json":
            case "avro":
                final RowType rowType = (RowType) valueDataType.getLogicalType();

                final org.apache.avro.Schema schema = AvroSchemaConverter.convertToSchema(rowType);
                byte[] schemaBytes = schema.toString().getBytes(StandardCharsets.UTF_8);
                SchemaInfo si = new SchemaInfo();
                si.setName("Record");
                si.setSchema(schemaBytes);
                si.setType(SchemaType.AVRO);
                return new FlinkSchema<>(si, valueSerialization, null);
            default:
                throw new UnsupportedOperationException(
                        "Generic schema is not supported on schema type " + valueFormatType + "'");
        }
    }

    // --------------------------------------------------------------------------------------------

    interface MetadataConverter extends Serializable {
        Object read(RowData consumedRow, int pos);
    }
}
