/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.search.aggregations.metrics;

import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.TotalHits.Relation;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.lucene.search.TopDocsAndMaxScore;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Results of the {@link TopHitsAggregator}.
 */
public class InternalTopHits extends InternalAggregation implements TopHits {
    private int from;
    private int size;
    private TopDocsAndMaxScore topDocs;
    private SearchHits searchHits;

    public InternalTopHits(
        String name,
        int from,
        int size,
        TopDocsAndMaxScore topDocs,
        SearchHits searchHits,
        Map<String, Object> metadata
    ) {
        super(name, metadata);
        this.from = from;
        this.size = size;
        this.topDocs = topDocs;
        this.searchHits = searchHits;
    }

    /**
     * Read from a stream.
     */
    public InternalTopHits(StreamInput in) throws IOException {
        super(in);
        from = in.readVInt();
        size = in.readVInt();
        topDocs = Lucene.readTopDocs(in);
        searchHits = new SearchHits(in);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeVInt(from);
        out.writeVInt(size);
        Lucene.writeTopDocs(out, topDocs);
        searchHits.writeTo(out);
    }

    @Override
    public String getWriteableName() {
        return TopHitsAggregationBuilder.NAME;
    }

    @Override
    public SearchHits getHits() {
        return searchHits;
    }

    TopDocsAndMaxScore getTopDocs() {
        return topDocs;
    }

    int getFrom() {
        return from;
    }

    int getSize() {
        return size;
    }

    @Override
    public InternalAggregation reduce(List<InternalAggregation> aggregations, ReduceContext reduceContext) {
        final SearchHits[] shardHits = new SearchHits[aggregations.size()];
        final int from;
        final int size;
        if (reduceContext.isFinalReduce()) {
            from = this.from;
            size = this.size;
        } else {
            // if we are not in the final reduce we need to ensure we maintain all possible elements during reduce
            // hence for pagination we need to maintain all hits until we are in the final phase.
            from = 0;
            size = this.from + this.size;
        }

        final TopDocs reducedTopDocs;
        final TopDocs[] shardDocs;

        if (topDocs.topDocs instanceof TopFieldDocs) {
            Sort sort = new Sort(((TopFieldDocs) topDocs.topDocs).fields);
            shardDocs = new TopFieldDocs[aggregations.size()];
            for (int i = 0; i < shardDocs.length; i++) {
                InternalTopHits topHitsAgg = (InternalTopHits) aggregations.get(i);
                shardDocs[i] = topHitsAgg.topDocs.topDocs;
                shardHits[i] = topHitsAgg.searchHits;
                for (ScoreDoc doc : shardDocs[i].scoreDocs) {
                    doc.shardIndex = i;
                }
            }
            reducedTopDocs = TopDocs.merge(sort, from, size, (TopFieldDocs[]) shardDocs);
        } else {
            shardDocs = new TopDocs[aggregations.size()];
            for (int i = 0; i < shardDocs.length; i++) {
                InternalTopHits topHitsAgg = (InternalTopHits) aggregations.get(i);
                shardDocs[i] = topHitsAgg.topDocs.topDocs;
                shardHits[i] = topHitsAgg.searchHits;
                for (ScoreDoc doc : shardDocs[i].scoreDocs) {
                    doc.shardIndex = i;
                }
            }
            reducedTopDocs = TopDocs.merge(from, size, shardDocs);
        }

        float maxScore = Float.NaN;
        for (InternalAggregation agg : aggregations) {
            InternalTopHits topHitsAgg = (InternalTopHits) agg;
            if (Float.isNaN(topHitsAgg.topDocs.maxScore) == false) {
                if (Float.isNaN(maxScore)) {
                    maxScore = topHitsAgg.topDocs.maxScore;
                } else {
                    maxScore = Math.max(maxScore, topHitsAgg.topDocs.maxScore);
                }
            }
        }

        final int[] tracker = new int[shardHits.length];
        SearchHit[] hits = new SearchHit[reducedTopDocs.scoreDocs.length];
        for (int i = 0; i < reducedTopDocs.scoreDocs.length; i++) {
            ScoreDoc scoreDoc = reducedTopDocs.scoreDocs[i];
            int position;
            do {
                position = tracker[scoreDoc.shardIndex]++;
            } while (shardDocs[scoreDoc.shardIndex].scoreDocs[position] != scoreDoc);
            hits[i] = shardHits[scoreDoc.shardIndex].getAt(position);
        }
        assert reducedTopDocs.totalHits.relation == Relation.EQUAL_TO;
        return new InternalTopHits(
            name,
            this.from,
            this.size,
            new TopDocsAndMaxScore(reducedTopDocs, maxScore),
            new SearchHits(hits, reducedTopDocs.totalHits, maxScore),
            getMetadata()
        );
    }

    @Override
    protected boolean mustReduceOnSingleInternalAgg() {
        return true;
    }

    @Override
    public Object getProperty(List<String> path) {
        if (path.isEmpty()) {
            return this;
        } else {
            throw new IllegalArgumentException("path not supported for [" + getName() + "]: " + path);
        }
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        searchHits.toXContent(builder, params);
        return builder;
    }

    // Equals and hashcode implemented for testing round trips
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        if (super.equals(obj) == false) return false;

        InternalTopHits other = (InternalTopHits) obj;
        if (from != other.from) return false;
        if (size != other.size) return false;
        if (topDocs.topDocs.totalHits.value != other.topDocs.topDocs.totalHits.value) return false;
        if (topDocs.topDocs.totalHits.relation != other.topDocs.topDocs.totalHits.relation) return false;
        if (topDocs.topDocs.scoreDocs.length != other.topDocs.topDocs.scoreDocs.length) return false;
        for (int d = 0; d < topDocs.topDocs.scoreDocs.length; d++) {
            ScoreDoc thisDoc = topDocs.topDocs.scoreDocs[d];
            ScoreDoc otherDoc = other.topDocs.topDocs.scoreDocs[d];
            if (thisDoc.doc != otherDoc.doc) return false;
            if (Double.compare(thisDoc.score, otherDoc.score) != 0) return false;
            if (thisDoc instanceof FieldDoc) {
                if (false == (otherDoc instanceof FieldDoc)) return false;
                FieldDoc thisFieldDoc = (FieldDoc) thisDoc;
                FieldDoc otherFieldDoc = (FieldDoc) otherDoc;
                if (thisFieldDoc.fields.length != otherFieldDoc.fields.length) return false;
                for (int f = 0; f < thisFieldDoc.fields.length; f++) {
                    if (false == thisFieldDoc.fields[f].equals(otherFieldDoc.fields[f])) return false;
                }
            }
        }
        return searchHits.equals(other.searchHits);
    }

    @Override
    public int hashCode() {
        int hashCode = super.hashCode();
        hashCode = 31 * hashCode + Integer.hashCode(from);
        hashCode = 31 * hashCode + Integer.hashCode(size);
        hashCode = 31 * hashCode + Long.hashCode(topDocs.topDocs.totalHits.value);
        hashCode = 31 * hashCode + topDocs.topDocs.totalHits.relation.hashCode();
        for (int d = 0; d < topDocs.topDocs.scoreDocs.length; d++) {
            ScoreDoc doc = topDocs.topDocs.scoreDocs[d];
            hashCode = 31 * hashCode + doc.doc;
            hashCode = 31 * hashCode + Float.floatToIntBits(doc.score);
            if (doc instanceof FieldDoc) {
                FieldDoc fieldDoc = (FieldDoc) doc;
                hashCode = 31 * hashCode + Arrays.hashCode(fieldDoc.fields);
            }
        }
        hashCode = 31 * hashCode + searchHits.hashCode();
        return hashCode;
    }
}
