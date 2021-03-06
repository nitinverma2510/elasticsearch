/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.FieldType;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.analysis.NamedAnalyzer;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeBooleanValue;
import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeIntegerValue;
import static org.elasticsearch.index.mapper.TypeParsers.parseField;

/**
 * A {@link FieldMapper} that takes a string and writes a count of the tokens in that string
 * to the index.  In most ways the mapper acts just like an {@link NumberFieldMapper}.
 */
public class TokenCountFieldMapper extends FieldMapper {
    public static final String CONTENT_TYPE = "token_count";

    public static class Defaults {
        public static final boolean DEFAULT_POSITION_INCREMENTS = true;
    }

    public static class Builder extends FieldMapper.Builder<Builder> {
        private NamedAnalyzer analyzer;
        private Integer nullValue;
        private boolean enablePositionIncrements = Defaults.DEFAULT_POSITION_INCREMENTS;

        public Builder(String name) {
            super(name, new FieldType());
            builder = this;
        }

        public Builder analyzer(NamedAnalyzer analyzer) {
            this.analyzer = analyzer;
            return this;
        }

        public NamedAnalyzer analyzer() {
            return analyzer;
        }

        public Builder enablePositionIncrements(boolean enablePositionIncrements) {
            this.enablePositionIncrements = enablePositionIncrements;
            return this;
        }

        public boolean enablePositionIncrements() {
            return enablePositionIncrements;
        }

        public Builder nullValue(Integer nullValue) {
            this.nullValue = nullValue;
            return this;
        }

        @Override
        public TokenCountFieldMapper build(BuilderContext context) {
            return new TokenCountFieldMapper(name, fieldType,
                new NumberFieldMapper.NumberFieldType(buildFullName(context), NumberFieldMapper.NumberType.INTEGER),
                analyzer, enablePositionIncrements, nullValue,
                multiFieldsBuilder.build(this, context), copyTo);
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder<?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            TokenCountFieldMapper.Builder builder = new TokenCountFieldMapper.Builder(name);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String propName = entry.getKey();
                Object propNode = entry.getValue();
                if (propName.equals("null_value")) {
                    builder.nullValue(nodeIntegerValue(propNode));
                    iterator.remove();
                } else if (propName.equals("analyzer")) {
                    NamedAnalyzer analyzer = parserContext.getIndexAnalyzers().get(propNode.toString());
                    if (analyzer == null) {
                        throw new MapperParsingException("Analyzer [" + propNode.toString() + "] not found for field [" + name + "]");
                    }
                    builder.analyzer(analyzer);
                    iterator.remove();
                } else if (propName.equals("enable_position_increments")) {
                    builder.enablePositionIncrements(nodeBooleanValue(propNode));
                    iterator.remove();
                }
            }
            parseField(builder, name, node, parserContext);
            if (builder.analyzer() == null) {
                throw new MapperParsingException("Analyzer must be set for field [" + name + "] but wasn't.");
            }
            return builder;
        }
    }

    private NamedAnalyzer analyzer;
    private final boolean enablePositionIncrements;
    private Integer nullValue;

    protected TokenCountFieldMapper(String simpleName, FieldType fieldType, MappedFieldType defaultFieldType,
                                    NamedAnalyzer analyzer, boolean enablePositionIncrements, Integer nullValue,
                                    MultiFields multiFields, CopyTo copyTo) {
        super(simpleName, fieldType, defaultFieldType, multiFields, copyTo);
        this.analyzer = analyzer;
        this.enablePositionIncrements = enablePositionIncrements;
        this.nullValue = nullValue;
    }

    @Override
    protected void parseCreateField(ParseContext context) throws IOException {
        final String value;
        if (context.externalValueSet()) {
            value = context.externalValue().toString();
        } else {
            value = context.parser().textOrNull();
        }

        if (value == null && nullValue == null) {
            return;
        }

        final int tokenCount;
        if (value == null) {
            tokenCount = nullValue;
        } else {
            tokenCount = countPositions(analyzer, name(), value, enablePositionIncrements);
        }

        boolean indexed = fieldType().isSearchable();
        boolean docValued = fieldType().hasDocValues();
        boolean stored = fieldType.stored();
        context.doc().addAll(NumberFieldMapper.NumberType.INTEGER.createFields(fieldType().name(), tokenCount, indexed, docValued, stored));
    }

    @Override
    public ValueFetcher valueFetcher(MapperService mapperService, String format) {
        if (format != null) {
            throw new IllegalArgumentException("Field [" + name() + "] of type [" + typeName() + "] doesn't support formats.");
        }

        return new SourceValueFetcher(name(), mapperService, parsesArrayValue(), nullValue) {
            @Override
            protected String parseSourceValue(Object value) {
                return value.toString();
            }
        };
    }

    /**
     * Count position increments in a token stream.  Package private for testing.
     * @param analyzer analyzer to create token stream
     * @param fieldName field name to pass to analyzer
     * @param fieldValue field value to pass to analyzer
     * @param enablePositionIncrements should we count position increments ?
     * @return number of position increments in a token stream
     * @throws IOException if tokenStream throws it
     */
    static int countPositions(Analyzer analyzer, String fieldName, String fieldValue, boolean enablePositionIncrements) throws IOException {
        try (TokenStream tokenStream = analyzer.tokenStream(fieldName, fieldValue)) {
            int count = 0;
            PositionIncrementAttribute position = tokenStream.addAttribute(PositionIncrementAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                if (enablePositionIncrements) {
                    count += position.getPositionIncrement();
                } else {
                    count += Math.min(1, position.getPositionIncrement());
                }
            }
            tokenStream.end();
            if (enablePositionIncrements) {
                count += position.getPositionIncrement();
            }
            return count;
        }
    }

    /**
     * Name of analyzer.
     * @return name of analyzer
     */
    public String analyzer() {
        return analyzer.name();
    }

    /**
     * Indicates if position increments are counted.
     * @return <code>true</code> if position increments are counted
     */
    public boolean enablePositionIncrements() {
        return enablePositionIncrements;
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    protected void mergeOptions(FieldMapper other, List<String> conflicts) {
        // TODO we should ban updating analyzers and null values as well
        if (this.enablePositionIncrements != ((TokenCountFieldMapper)other).enablePositionIncrements) {
            conflicts.add("mapper [" + name() + "] has a different [enable_position_increments] setting");
        }
        this.analyzer = ((TokenCountFieldMapper)other).analyzer;
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);
        builder.field("analyzer", analyzer());
        if (includeDefaults || enablePositionIncrements() != Defaults.DEFAULT_POSITION_INCREMENTS) {
            builder.field("enable_position_increments", enablePositionIncrements());
        }
    }

}
