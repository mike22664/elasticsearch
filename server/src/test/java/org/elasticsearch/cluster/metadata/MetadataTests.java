/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.coordination.CoordinationMetadata;
import org.elasticsearch.cluster.coordination.CoordinationMetadata.VotingConfigExclusion;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.NamedWriteableAwareStreamInput;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.alias.RandomAliasActionsGenerator;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.plugins.MapperPlugin;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.metadata.DataStreamTestHelper.createBackingIndex;
import static org.elasticsearch.cluster.metadata.DataStreamTestHelper.createFirstBackingIndex;
import static org.elasticsearch.cluster.metadata.DataStreamTestHelper.createTimestampField;
import static org.elasticsearch.cluster.metadata.Metadata.Builder.validateDataStreams;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

public class MetadataTests extends ESTestCase {

    public void testFindAliases() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("index")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .numberOfShards(1)
                    .numberOfReplicas(0)
                    .putAlias(AliasMetadata.builder("alias1").build())
                    .putAlias(AliasMetadata.builder("alias2").build())
            )
            .build();

        {
            GetAliasesRequest request = new GetAliasesRequest();
            ImmutableOpenMap<String, List<AliasMetadata>> aliases = metadata.findAliases(request.aliases(), Strings.EMPTY_ARRAY);
            assertThat(aliases.size(), equalTo(0));
        }
        {
            final GetAliasesRequest request;
            if (randomBoolean()) {
                request = new GetAliasesRequest();
            } else {
                request = new GetAliasesRequest(randomFrom("alias1", "alias2"));
                // replacing with empty aliases behaves as if aliases were unspecified at request building
                request.replaceAliases(Strings.EMPTY_ARRAY);
            }
            ImmutableOpenMap<String, List<AliasMetadata>> aliases = metadata.findAliases(request.aliases(), new String[] { "index" });
            assertThat(aliases.size(), equalTo(1));
            List<AliasMetadata> aliasMetadataList = aliases.get("index");
            assertThat(aliasMetadataList.size(), equalTo(2));
            assertThat(aliasMetadataList.get(0).alias(), equalTo("alias1"));
            assertThat(aliasMetadataList.get(1).alias(), equalTo("alias2"));
        }
        {
            GetAliasesRequest request = new GetAliasesRequest("alias*");
            ImmutableOpenMap<String, List<AliasMetadata>> aliases = metadata.findAliases(request.aliases(), new String[] { "index" });
            assertThat(aliases.size(), equalTo(1));
            List<AliasMetadata> aliasMetadataList = aliases.get("index");
            assertThat(aliasMetadataList.size(), equalTo(2));
            assertThat(aliasMetadataList.get(0).alias(), equalTo("alias1"));
            assertThat(aliasMetadataList.get(1).alias(), equalTo("alias2"));
        }
        {
            GetAliasesRequest request = new GetAliasesRequest("alias1");
            ImmutableOpenMap<String, List<AliasMetadata>> aliases = metadata.findAliases(request.aliases(), new String[] { "index" });
            assertThat(aliases.size(), equalTo(1));
            List<AliasMetadata> aliasMetadataList = aliases.get("index");
            assertThat(aliasMetadataList.size(), equalTo(1));
            assertThat(aliasMetadataList.get(0).alias(), equalTo("alias1"));
        }
        {
            ImmutableOpenMap<String, List<AliasMetadata>> aliases = metadata.findAllAliases(new String[] { "index" });
            assertThat(aliases.size(), equalTo(1));
            List<AliasMetadata> aliasMetadataList = aliases.get("index");
            assertThat(aliasMetadataList.size(), equalTo(2));
            assertThat(aliasMetadataList.get(0).alias(), equalTo("alias1"));
            assertThat(aliasMetadataList.get(1).alias(), equalTo("alias2"));
        }
        {
            ImmutableOpenMap<String, List<AliasMetadata>> aliases = metadata.findAllAliases(Strings.EMPTY_ARRAY);
            assertThat(aliases.size(), equalTo(0));
        }
    }

    public void testFindAliasWithExclusion() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("index")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .numberOfShards(1)
                    .numberOfReplicas(0)
                    .putAlias(AliasMetadata.builder("alias1").build())
                    .putAlias(AliasMetadata.builder("alias2").build())
            )
            .build();
        GetAliasesRequest request = new GetAliasesRequest().aliases("*", "-alias1");
        List<AliasMetadata> aliases = metadata.findAliases(request.aliases(), new String[] { "index" }).get("index");
        assertThat(aliases.size(), equalTo(1));
        assertThat(aliases.get(0).alias(), equalTo("alias2"));
    }

    public void testFindDataStreams() {
        final int numIndices = randomIntBetween(2, 5);
        final int numBackingIndices = randomIntBetween(2, 5);
        final String dataStreamName = "my-data-stream";
        CreateIndexResult result = createIndices(numIndices, numBackingIndices, dataStreamName);

        List<Index> allIndices = new ArrayList<>(result.indices);
        allIndices.addAll(result.backingIndices);
        String[] concreteIndices = allIndices.stream().map(Index::getName).collect(Collectors.toList()).toArray(new String[] {});
        ImmutableOpenMap<String, IndexAbstraction.DataStream> dataStreams = result.metadata.findDataStreams(concreteIndices);
        assertThat(dataStreams.size(), equalTo(numBackingIndices));
        for (Index backingIndex : result.backingIndices) {
            assertThat(dataStreams.containsKey(backingIndex.getName()), is(true));
            assertThat(dataStreams.get(backingIndex.getName()).getName(), equalTo(dataStreamName));
        }
    }

    public void testFindAliasWithExclusionAndOverride() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("index")
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .numberOfShards(1)
                    .numberOfReplicas(0)
                    .putAlias(AliasMetadata.builder("aa").build())
                    .putAlias(AliasMetadata.builder("ab").build())
                    .putAlias(AliasMetadata.builder("bb").build())
            )
            .build();
        GetAliasesRequest request = new GetAliasesRequest().aliases("a*", "-*b", "b*");
        List<AliasMetadata> aliases = metadata.findAliases(request.aliases(), new String[] { "index" }).get("index");
        assertThat(aliases.size(), equalTo(2));
        assertThat(aliases.get(0).alias(), equalTo("aa"));
        assertThat(aliases.get(1).alias(), equalTo("bb"));
    }

    public void testIndexAndAliasWithSameName() {
        IndexMetadata.Builder builder = IndexMetadata.builder("index")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(AliasMetadata.builder("index").build());
        try {
            Metadata.builder().put(builder).build();
            fail("exception should have been thrown");
        } catch (IllegalStateException e) {
            assertThat(
                e.getMessage(),
                equalTo(
                    "index, alias, and data stream names need to be unique, but the following duplicates were found [index (alias "
                        + "of [index]) conflicts with index]"
                )
            );
        }
    }

    public void testAliasCollidingWithAnExistingIndex() {
        int indexCount = randomIntBetween(10, 100);
        Set<String> indices = new HashSet<>(indexCount);
        for (int i = 0; i < indexCount; i++) {
            indices.add(randomAlphaOfLength(10));
        }
        Map<String, Set<String>> aliasToIndices = new HashMap<>();
        for (String alias : randomSubsetOf(randomIntBetween(1, 10), indices)) {
            aliasToIndices.put(alias, new HashSet<>(randomSubsetOf(randomIntBetween(1, 3), indices)));
        }
        int properAliases = randomIntBetween(0, 3);
        for (int i = 0; i < properAliases; i++) {
            aliasToIndices.put(randomAlphaOfLength(5), new HashSet<>(randomSubsetOf(randomIntBetween(1, 3), indices)));
        }
        Metadata.Builder metadataBuilder = Metadata.builder();
        for (String index : indices) {
            IndexMetadata.Builder indexBuilder = IndexMetadata.builder(index)
                .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                .numberOfShards(1)
                .numberOfReplicas(0);
            aliasToIndices.forEach((key, value) -> {
                if (value.contains(index)) {
                    indexBuilder.putAlias(AliasMetadata.builder(key).build());
                }
            });
            metadataBuilder.put(indexBuilder);
        }
        try {
            metadataBuilder.build();
            fail("exception should have been thrown");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), startsWith("index, alias, and data stream names need to be unique"));
        }
    }

    public void testValidateAliasWriteOnly() {
        String alias = randomAlphaOfLength(5);
        String indexA = randomAlphaOfLength(6);
        String indexB = randomAlphaOfLength(7);
        Boolean aWriteIndex = randomBoolean() ? null : randomBoolean();
        Boolean bWriteIndex;
        if (Boolean.TRUE.equals(aWriteIndex)) {
            bWriteIndex = randomFrom(Boolean.FALSE, null);
        } else {
            bWriteIndex = randomFrom(Boolean.TRUE, Boolean.FALSE, null);
        }
        // when only one index/alias pair exist
        Metadata metadata = Metadata.builder().put(buildIndexMetadata(indexA, alias, aWriteIndex)).build();

        // when alias points to two indices, but valid
        // one of the following combinations: [(null, null), (null, true), (null, false), (false, false)]
        Metadata.builder(metadata).put(buildIndexMetadata(indexB, alias, bWriteIndex)).build();

        // when too many write indices
        Exception exception = expectThrows(IllegalStateException.class, () -> {
            IndexMetadata.Builder metaA = buildIndexMetadata(indexA, alias, true);
            IndexMetadata.Builder metaB = buildIndexMetadata(indexB, alias, true);
            Metadata.builder().put(metaA).put(metaB).build();
        });
        assertThat(exception.getMessage(), startsWith("alias [" + alias + "] has more than one write index ["));
    }

    public void testValidateHiddenAliasConsistency() {
        String alias = randomAlphaOfLength(5);
        String indexA = randomAlphaOfLength(6);
        String indexB = randomAlphaOfLength(7);

        {
            Exception ex = expectThrows(
                IllegalStateException.class,
                () -> buildMetadataWithHiddenIndexMix(alias, indexA, true, indexB, randomFrom(false, null)).build()
            );
            assertThat(ex.getMessage(), containsString("has is_hidden set to true on indices"));
        }

        {
            Exception ex = expectThrows(
                IllegalStateException.class,
                () -> buildMetadataWithHiddenIndexMix(alias, indexA, randomFrom(false, null), indexB, true).build()
            );
            assertThat(ex.getMessage(), containsString("has is_hidden set to true on indices"));
        }
    }

    private Metadata.Builder buildMetadataWithHiddenIndexMix(
        String aliasName,
        String indexAName,
        Boolean indexAHidden,
        String indexBName,
        Boolean indexBHidden
    ) {
        IndexMetadata.Builder indexAMeta = IndexMetadata.builder(indexAName)
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(AliasMetadata.builder(aliasName).isHidden(indexAHidden).build());
        IndexMetadata.Builder indexBMeta = IndexMetadata.builder(indexBName)
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(AliasMetadata.builder(aliasName).isHidden(indexBHidden).build());
        return Metadata.builder().put(indexAMeta).put(indexBMeta);
    }

    public void testResolveIndexRouting() {
        IndexMetadata.Builder builder = IndexMetadata.builder("index")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(AliasMetadata.builder("alias0").build())
            .putAlias(AliasMetadata.builder("alias1").routing("1").build())
            .putAlias(AliasMetadata.builder("alias2").routing("1,2").build());
        Metadata metadata = Metadata.builder().put(builder).build();

        // no alias, no index
        assertEquals(metadata.resolveIndexRouting(null, null), null);
        assertEquals(metadata.resolveIndexRouting("0", null), "0");

        // index, no alias
        assertEquals(metadata.resolveIndexRouting(null, "index"), null);
        assertEquals(metadata.resolveIndexRouting("0", "index"), "0");

        // alias with no index routing
        assertEquals(metadata.resolveIndexRouting(null, "alias0"), null);
        assertEquals(metadata.resolveIndexRouting("0", "alias0"), "0");

        // alias with index routing.
        assertEquals(metadata.resolveIndexRouting(null, "alias1"), "1");
        try {
            metadata.resolveIndexRouting("0", "alias1");
            fail("should fail");
        } catch (IllegalArgumentException ex) {
            assertThat(
                ex.getMessage(),
                is(
                    "Alias [alias1] has index routing associated with it [1], "
                        + "and was provided with routing value [0], rejecting operation"
                )
            );
        }

        // alias with invalid index routing.
        try {
            metadata.resolveIndexRouting(null, "alias2");
            fail("should fail");
        } catch (IllegalArgumentException ex) {
            assertThat(
                ex.getMessage(),
                is(
                    "index/alias [alias2] provided with routing value [1,2] that"
                        + " resolved to several routing values, rejecting operation"
                )
            );
        }

        try {
            metadata.resolveIndexRouting("1", "alias2");
            fail("should fail");
        } catch (IllegalArgumentException ex) {
            assertThat(
                ex.getMessage(),
                is(
                    "index/alias [alias2] provided with routing value [1,2] that"
                        + " resolved to several routing values, rejecting operation"
                )
            );
        }

        IndexMetadata.Builder builder2 = IndexMetadata.builder("index2")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(AliasMetadata.builder("alias0").build());
        Metadata metadataTwoIndices = Metadata.builder(metadata).put(builder2).build();

        // alias with multiple indices
        IllegalArgumentException exception = expectThrows(
            IllegalArgumentException.class,
            () -> metadataTwoIndices.resolveIndexRouting("1", "alias0")
        );
        assertThat(exception.getMessage(), startsWith("Alias [alias0] has more than one index associated with it"));
    }

    public void testResolveWriteIndexRouting() {
        AliasMetadata.Builder aliasZeroBuilder = AliasMetadata.builder("alias0");
        if (randomBoolean()) {
            aliasZeroBuilder.writeIndex(true);
        }
        IndexMetadata.Builder builder = IndexMetadata.builder("index")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(aliasZeroBuilder.build())
            .putAlias(AliasMetadata.builder("alias1").routing("1").build())
            .putAlias(AliasMetadata.builder("alias2").routing("1,2").build())
            .putAlias(AliasMetadata.builder("alias3").writeIndex(false).build())
            .putAlias(AliasMetadata.builder("alias4").routing("1,2").writeIndex(true).build());
        Metadata metadata = Metadata.builder().put(builder).build();

        // no alias, no index
        assertEquals(metadata.resolveWriteIndexRouting(null, null), null);
        assertEquals(metadata.resolveWriteIndexRouting("0", null), "0");

        // index, no alias
        assertEquals(metadata.resolveWriteIndexRouting(null, "index"), null);
        assertEquals(metadata.resolveWriteIndexRouting("0", "index"), "0");

        // alias with no index routing
        assertEquals(metadata.resolveWriteIndexRouting(null, "alias0"), null);
        assertEquals(metadata.resolveWriteIndexRouting("0", "alias0"), "0");

        // alias with index routing.
        assertEquals(metadata.resolveWriteIndexRouting(null, "alias1"), "1");
        Exception exception = expectThrows(IllegalArgumentException.class, () -> metadata.resolveWriteIndexRouting("0", "alias1"));
        assertThat(
            exception.getMessage(),
            is("Alias [alias1] has index routing associated with it [1], and was provided with routing value [0], rejecting operation")
        );

        // alias with invalid index routing.
        exception = expectThrows(IllegalArgumentException.class, () -> metadata.resolveWriteIndexRouting(null, "alias2"));
        assertThat(
            exception.getMessage(),
            is("index/alias [alias2] provided with routing value [1,2] that resolved to several routing values, rejecting operation")
        );
        exception = expectThrows(IllegalArgumentException.class, () -> metadata.resolveWriteIndexRouting("1", "alias2"));
        assertThat(
            exception.getMessage(),
            is("index/alias [alias2] provided with routing value [1,2] that resolved to several routing values, rejecting operation")
        );
        exception = expectThrows(IllegalArgumentException.class, () -> metadata.resolveWriteIndexRouting(randomFrom("1", null), "alias4"));
        assertThat(
            exception.getMessage(),
            is("index/alias [alias4] provided with routing value [1,2] that resolved to several routing values, rejecting operation")
        );

        // alias with no write index
        exception = expectThrows(IllegalArgumentException.class, () -> metadata.resolveWriteIndexRouting("1", "alias3"));
        assertThat(exception.getMessage(), is("alias [alias3] does not have a write index"));

        // aliases with multiple indices
        AliasMetadata.Builder aliasZeroBuilderTwo = AliasMetadata.builder("alias0");
        if (randomBoolean()) {
            aliasZeroBuilder.writeIndex(false);
        }
        IndexMetadata.Builder builder2 = IndexMetadata.builder("index2")
            .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(aliasZeroBuilderTwo.build())
            .putAlias(AliasMetadata.builder("alias1").routing("0").writeIndex(true).build())
            .putAlias(AliasMetadata.builder("alias2").writeIndex(true).build());
        Metadata metadataTwoIndices = Metadata.builder(metadata).put(builder2).build();

        // verify that new write index is used
        assertThat("0", equalTo(metadataTwoIndices.resolveWriteIndexRouting("0", "alias1")));
    }

    public void testUnknownFieldClusterMetadata() throws IOException {
        BytesReference metadata = BytesReference.bytes(
            JsonXContent.contentBuilder().startObject().startObject("meta-data").field("random", "value").endObject().endObject()
        );
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, metadata)) {
            Metadata.Builder.fromXContent(parser);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Unexpected field [random]", e.getMessage());
        }
    }

    public void testUnknownFieldIndexMetadata() throws IOException {
        BytesReference metadata = BytesReference.bytes(
            JsonXContent.contentBuilder().startObject().startObject("index_name").field("random", "value").endObject().endObject()
        );
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, metadata)) {
            IndexMetadata.Builder.fromXContent(parser);
            fail();
        } catch (IllegalArgumentException e) {
            assertEquals("Unexpected field [random]", e.getMessage());
        }
    }

    public void testMetadataGlobalStateChangesOnIndexDeletions() {
        IndexGraveyard.Builder builder = IndexGraveyard.builder();
        builder.addTombstone(new Index("idx1", UUIDs.randomBase64UUID()));
        final Metadata metadata1 = Metadata.builder().indexGraveyard(builder.build()).build();
        builder = IndexGraveyard.builder(metadata1.indexGraveyard());
        builder.addTombstone(new Index("idx2", UUIDs.randomBase64UUID()));
        final Metadata metadata2 = Metadata.builder(metadata1).indexGraveyard(builder.build()).build();
        assertFalse("metadata not equal after adding index deletions", Metadata.isGlobalStateEquals(metadata1, metadata2));
        final Metadata metadata3 = Metadata.builder(metadata2).build();
        assertTrue("metadata equal when not adding index deletions", Metadata.isGlobalStateEquals(metadata2, metadata3));
    }

    public void testXContentWithIndexGraveyard() throws IOException {
        final IndexGraveyard graveyard = IndexGraveyardTests.createRandom();
        final Metadata originalMeta = Metadata.builder().indexGraveyard(graveyard).build();
        final XContentBuilder builder = JsonXContent.contentBuilder();
        builder.startObject();
        Metadata.FORMAT.toXContent(builder, originalMeta);
        builder.endObject();
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, BytesReference.bytes(builder))) {
            final Metadata fromXContentMeta = Metadata.fromXContent(parser);
            assertThat(fromXContentMeta.indexGraveyard(), equalTo(originalMeta.indexGraveyard()));
        }
    }

    public void testXContentClusterUUID() throws IOException {
        final Metadata originalMeta = Metadata.builder()
            .clusterUUID(UUIDs.randomBase64UUID())
            .clusterUUIDCommitted(randomBoolean())
            .build();
        final XContentBuilder builder = JsonXContent.contentBuilder();
        builder.startObject();
        Metadata.FORMAT.toXContent(builder, originalMeta);
        builder.endObject();
        try (XContentParser parser = createParser(JsonXContent.jsonXContent, BytesReference.bytes(builder))) {
            final Metadata fromXContentMeta = Metadata.fromXContent(parser);
            assertThat(fromXContentMeta.clusterUUID(), equalTo(originalMeta.clusterUUID()));
            assertThat(fromXContentMeta.clusterUUIDCommitted(), equalTo(originalMeta.clusterUUIDCommitted()));
        }
    }

    public void testSerializationClusterUUID() throws IOException {
        final Metadata originalMeta = Metadata.builder()
            .clusterUUID(UUIDs.randomBase64UUID())
            .clusterUUIDCommitted(randomBoolean())
            .build();
        final BytesStreamOutput out = new BytesStreamOutput();
        originalMeta.writeTo(out);
        NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(ClusterModule.getNamedWriteables());
        final Metadata fromStreamMeta = Metadata.readFrom(
            new NamedWriteableAwareStreamInput(out.bytes().streamInput(), namedWriteableRegistry)
        );
        assertThat(fromStreamMeta.clusterUUID(), equalTo(originalMeta.clusterUUID()));
        assertThat(fromStreamMeta.clusterUUIDCommitted(), equalTo(originalMeta.clusterUUIDCommitted()));
    }

    public void testMetadataGlobalStateChangesOnClusterUUIDChanges() {
        final Metadata metadata1 = Metadata.builder().clusterUUID(UUIDs.randomBase64UUID()).clusterUUIDCommitted(randomBoolean()).build();
        final Metadata metadata2 = Metadata.builder(metadata1).clusterUUID(UUIDs.randomBase64UUID()).build();
        final Metadata metadata3 = Metadata.builder(metadata1).clusterUUIDCommitted(metadata1.clusterUUIDCommitted() == false).build();
        assertFalse(Metadata.isGlobalStateEquals(metadata1, metadata2));
        assertFalse(Metadata.isGlobalStateEquals(metadata1, metadata3));
        final Metadata metadata4 = Metadata.builder(metadata2).clusterUUID(metadata1.clusterUUID()).build();
        assertTrue(Metadata.isGlobalStateEquals(metadata1, metadata4));
    }

    private static CoordinationMetadata.VotingConfiguration randomVotingConfig() {
        return new CoordinationMetadata.VotingConfiguration(Sets.newHashSet(generateRandomStringArray(randomInt(10), 20, false)));
    }

    private Set<VotingConfigExclusion> randomVotingConfigExclusions() {
        final int size = randomIntBetween(0, 10);
        final Set<VotingConfigExclusion> nodes = new HashSet<>(size);
        while (nodes.size() < size) {
            assertTrue(nodes.add(new VotingConfigExclusion(randomAlphaOfLength(10), randomAlphaOfLength(10))));
        }
        return nodes;
    }

    public void testXContentWithCoordinationMetadata() throws IOException {
        CoordinationMetadata originalMeta = new CoordinationMetadata(
            randomNonNegativeLong(),
            randomVotingConfig(),
            randomVotingConfig(),
            randomVotingConfigExclusions()
        );

        Metadata metadata = Metadata.builder().coordinationMetadata(originalMeta).build();

        final XContentBuilder builder = JsonXContent.contentBuilder();
        builder.startObject();
        Metadata.FORMAT.toXContent(builder, metadata);
        builder.endObject();

        try (XContentParser parser = createParser(JsonXContent.jsonXContent, BytesReference.bytes(builder))) {
            final CoordinationMetadata fromXContentMeta = Metadata.fromXContent(parser).coordinationMetadata();
            assertThat(fromXContentMeta, equalTo(originalMeta));
        }
    }

    public void testGlobalStateEqualsCoordinationMetadata() {
        CoordinationMetadata coordinationMetadata1 = new CoordinationMetadata(
            randomNonNegativeLong(),
            randomVotingConfig(),
            randomVotingConfig(),
            randomVotingConfigExclusions()
        );
        Metadata metadata1 = Metadata.builder().coordinationMetadata(coordinationMetadata1).build();
        CoordinationMetadata coordinationMetadata2 = new CoordinationMetadata(
            randomNonNegativeLong(),
            randomVotingConfig(),
            randomVotingConfig(),
            randomVotingConfigExclusions()
        );
        Metadata metadata2 = Metadata.builder().coordinationMetadata(coordinationMetadata2).build();

        assertTrue(Metadata.isGlobalStateEquals(metadata1, metadata1));
        assertFalse(Metadata.isGlobalStateEquals(metadata1, metadata2));
    }

    public void testSerializationWithIndexGraveyard() throws IOException {
        final IndexGraveyard graveyard = IndexGraveyardTests.createRandom();
        final Metadata originalMeta = Metadata.builder().indexGraveyard(graveyard).build();
        final BytesStreamOutput out = new BytesStreamOutput();
        originalMeta.writeTo(out);
        NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(ClusterModule.getNamedWriteables());
        final Metadata fromStreamMeta = Metadata.readFrom(
            new NamedWriteableAwareStreamInput(out.bytes().streamInput(), namedWriteableRegistry)
        );
        assertThat(fromStreamMeta.indexGraveyard(), equalTo(fromStreamMeta.indexGraveyard()));
    }

    public void testFindMappings() throws IOException {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("index1")
                    .settings(
                        Settings.builder()
                            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
                    .putMapping(FIND_MAPPINGS_TEST_ITEM)
            )
            .put(
                IndexMetadata.builder("index2")
                    .settings(
                        Settings.builder()
                            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
                    .putMapping(FIND_MAPPINGS_TEST_ITEM)
            )
            .build();

        {
            AtomicInteger onNextIndexCalls = new AtomicInteger(0);
            ImmutableOpenMap<String, MappingMetadata> mappings = metadata.findMappings(
                Strings.EMPTY_ARRAY,
                MapperPlugin.NOOP_FIELD_FILTER,
                onNextIndexCalls::incrementAndGet
            );
            assertEquals(0, mappings.size());
            assertThat(onNextIndexCalls.get(), equalTo(0));
        }
        {
            AtomicInteger onNextIndexCalls = new AtomicInteger(0);
            ImmutableOpenMap<String, MappingMetadata> mappings = metadata.findMappings(
                new String[] { "index1" },
                MapperPlugin.NOOP_FIELD_FILTER,
                onNextIndexCalls::incrementAndGet
            );
            assertEquals(1, mappings.size());
            assertIndexMappingsNotFiltered(mappings, "index1");
            assertThat(onNextIndexCalls.get(), equalTo(1));
        }
        {
            AtomicInteger onNextIndexCalls = new AtomicInteger(0);
            ImmutableOpenMap<String, MappingMetadata> mappings = metadata.findMappings(
                new String[] { "index1", "index2" },
                MapperPlugin.NOOP_FIELD_FILTER,
                onNextIndexCalls::incrementAndGet
            );
            assertEquals(2, mappings.size());
            assertIndexMappingsNotFiltered(mappings, "index1");
            assertIndexMappingsNotFiltered(mappings, "index2");
            assertThat(onNextIndexCalls.get(), equalTo(2));
        }
    }

    public void testFindMappingsNoOpFilters() throws IOException {
        MappingMetadata originalMappingMetadata = new MappingMetadata(
            "_doc",
            XContentHelper.convertToMap(JsonXContent.jsonXContent, FIND_MAPPINGS_TEST_ITEM, true)
        );

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("index1")
                    .settings(
                        Settings.builder()
                            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
                    .putMapping(originalMappingMetadata)
            )
            .build();

        {
            ImmutableOpenMap<String, MappingMetadata> mappings = metadata.findMappings(
                new String[] { "index1" },
                MapperPlugin.NOOP_FIELD_FILTER,
                Metadata.ON_NEXT_INDEX_FIND_MAPPINGS_NOOP
            );
            MappingMetadata mappingMetadata = mappings.get("index1");
            assertSame(originalMappingMetadata, mappingMetadata);
        }
        {
            ImmutableOpenMap<String, MappingMetadata> mappings = metadata.findMappings(
                new String[] { "index1" },
                index -> field -> randomBoolean(),
                Metadata.ON_NEXT_INDEX_FIND_MAPPINGS_NOOP
            );
            MappingMetadata mappingMetadata = mappings.get("index1");
            assertNotSame(originalMappingMetadata, mappingMetadata);
        }
    }

    @SuppressWarnings("unchecked")
    public void testFindMappingsWithFilters() throws IOException {
        String mapping = FIND_MAPPINGS_TEST_ITEM;
        if (randomBoolean()) {
            Map<String, Object> stringObjectMap = XContentHelper.convertToMap(JsonXContent.jsonXContent, FIND_MAPPINGS_TEST_ITEM, false);
            Map<String, Object> doc = (Map<String, Object>) stringObjectMap.get("_doc");
            try (XContentBuilder builder = JsonXContent.contentBuilder()) {
                builder.map(doc);
                mapping = Strings.toString(builder);
            }
        }

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("index1")
                    .settings(
                        Settings.builder()
                            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
                    .putMapping(mapping)
            )
            .put(
                IndexMetadata.builder("index2")
                    .settings(
                        Settings.builder()
                            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
                    .putMapping(mapping)
            )
            .put(
                IndexMetadata.builder("index3")
                    .settings(
                        Settings.builder()
                            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
                            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
                    .putMapping(mapping)
            )
            .build();

        {
            ImmutableOpenMap<String, MappingMetadata> mappings = metadata.findMappings(
                new String[] { "index1", "index2", "index3" },
                index -> {
                    if (index.equals("index1")) {
                        return field -> field.startsWith("name.") == false
                            && field.startsWith("properties.key.") == false
                            && field.equals("age") == false
                            && field.equals("address.location") == false;
                    }
                    if (index.equals("index2")) {
                        return field -> false;
                    }
                    return MapperPlugin.NOOP_FIELD_PREDICATE;
                },
                Metadata.ON_NEXT_INDEX_FIND_MAPPINGS_NOOP
            );

            assertIndexMappingsNoFields(mappings, "index2");
            assertIndexMappingsNotFiltered(mappings, "index3");

            MappingMetadata docMapping = mappings.get("index1");
            assertNotNull(docMapping);

            Map<String, Object> sourceAsMap = docMapping.getSourceAsMap();
            assertEquals(3, sourceAsMap.size());
            assertTrue(sourceAsMap.containsKey("_routing"));
            assertTrue(sourceAsMap.containsKey("_source"));

            Map<String, Object> typeProperties = (Map<String, Object>) sourceAsMap.get("properties");
            assertEquals(6, typeProperties.size());
            assertTrue(typeProperties.containsKey("birth"));
            assertTrue(typeProperties.containsKey("ip"));
            assertTrue(typeProperties.containsKey("suggest"));

            Map<String, Object> name = (Map<String, Object>) typeProperties.get("name");
            assertNotNull(name);
            assertEquals(1, name.size());
            Map<String, Object> nameProperties = (Map<String, Object>) name.get("properties");
            assertNotNull(nameProperties);
            assertEquals(0, nameProperties.size());

            Map<String, Object> address = (Map<String, Object>) typeProperties.get("address");
            assertNotNull(address);
            assertEquals(2, address.size());
            assertTrue(address.containsKey("type"));
            Map<String, Object> addressProperties = (Map<String, Object>) address.get("properties");
            assertNotNull(addressProperties);
            assertEquals(2, addressProperties.size());
            assertLeafs(addressProperties, "street", "area");

            Map<String, Object> properties = (Map<String, Object>) typeProperties.get("properties");
            assertNotNull(properties);
            assertEquals(2, properties.size());
            assertTrue(properties.containsKey("type"));
            Map<String, Object> propertiesProperties = (Map<String, Object>) properties.get("properties");
            assertNotNull(propertiesProperties);
            assertEquals(2, propertiesProperties.size());
            assertLeafs(propertiesProperties, "key");
            assertMultiField(propertiesProperties, "value", "keyword");
        }

        {
            ImmutableOpenMap<String, MappingMetadata> mappings = metadata.findMappings(
                new String[] { "index1", "index2", "index3" },
                index -> field -> (index.equals("index3") && field.endsWith("keyword")),
                Metadata.ON_NEXT_INDEX_FIND_MAPPINGS_NOOP
            );

            assertIndexMappingsNoFields(mappings, "index1");
            assertIndexMappingsNoFields(mappings, "index2");
            MappingMetadata mappingMetadata = mappings.get("index3");
            Map<String, Object> sourceAsMap = mappingMetadata.getSourceAsMap();
            assertEquals(3, sourceAsMap.size());
            assertTrue(sourceAsMap.containsKey("_routing"));
            assertTrue(sourceAsMap.containsKey("_source"));
            Map<String, Object> typeProperties = (Map<String, Object>) sourceAsMap.get("properties");
            assertNotNull(typeProperties);
            assertEquals(1, typeProperties.size());
            Map<String, Object> properties = (Map<String, Object>) typeProperties.get("properties");
            assertNotNull(properties);
            assertEquals(2, properties.size());
            assertTrue(properties.containsKey("type"));
            Map<String, Object> propertiesProperties = (Map<String, Object>) properties.get("properties");
            assertNotNull(propertiesProperties);
            assertEquals(2, propertiesProperties.size());
            Map<String, Object> key = (Map<String, Object>) propertiesProperties.get("key");
            assertEquals(1, key.size());
            Map<String, Object> keyProperties = (Map<String, Object>) key.get("properties");
            assertEquals(1, keyProperties.size());
            assertLeafs(keyProperties, "keyword");
            Map<String, Object> value = (Map<String, Object>) propertiesProperties.get("value");
            assertEquals(1, value.size());
            Map<String, Object> valueProperties = (Map<String, Object>) value.get("properties");
            assertEquals(1, valueProperties.size());
            assertLeafs(valueProperties, "keyword");
        }

        {
            ImmutableOpenMap<String, MappingMetadata> mappings = metadata.findMappings(
                new String[] { "index1", "index2", "index3" },
                index -> field -> (index.equals("index2")),
                Metadata.ON_NEXT_INDEX_FIND_MAPPINGS_NOOP
            );

            assertIndexMappingsNoFields(mappings, "index1");
            assertIndexMappingsNoFields(mappings, "index3");
            assertIndexMappingsNotFiltered(mappings, "index2");
        }
    }

    private static IndexMetadata.Builder buildIndexMetadata(String name, String alias, Boolean writeIndex) {
        return IndexMetadata.builder(name)
            .settings(settings(Version.CURRENT))
            .creationDate(randomNonNegativeLong())
            .putAlias(AliasMetadata.builder(alias).writeIndex(writeIndex))
            .numberOfShards(1)
            .numberOfReplicas(0);
    }

    @SuppressWarnings("unchecked")
    private static void assertIndexMappingsNoFields(ImmutableOpenMap<String, MappingMetadata> mappings, String index) {
        MappingMetadata docMapping = mappings.get(index);
        assertNotNull(docMapping);
        Map<String, Object> sourceAsMap = docMapping.getSourceAsMap();
        assertEquals(3, sourceAsMap.size());
        assertTrue(sourceAsMap.containsKey("_routing"));
        assertTrue(sourceAsMap.containsKey("_source"));
        Map<String, Object> typeProperties = (Map<String, Object>) sourceAsMap.get("properties");
        assertEquals(0, typeProperties.size());
    }

    @SuppressWarnings("unchecked")
    private static void assertIndexMappingsNotFiltered(ImmutableOpenMap<String, MappingMetadata> mappings, String index) {
        MappingMetadata docMapping = mappings.get(index);
        assertNotNull(docMapping);

        Map<String, Object> sourceAsMap = docMapping.getSourceAsMap();
        assertEquals(3, sourceAsMap.size());
        assertTrue(sourceAsMap.containsKey("_routing"));
        assertTrue(sourceAsMap.containsKey("_source"));

        Map<String, Object> typeProperties = (Map<String, Object>) sourceAsMap.get("properties");
        assertEquals(7, typeProperties.size());
        assertTrue(typeProperties.containsKey("birth"));
        assertTrue(typeProperties.containsKey("age"));
        assertTrue(typeProperties.containsKey("ip"));
        assertTrue(typeProperties.containsKey("suggest"));

        Map<String, Object> name = (Map<String, Object>) typeProperties.get("name");
        assertNotNull(name);
        assertEquals(1, name.size());
        Map<String, Object> nameProperties = (Map<String, Object>) name.get("properties");
        assertNotNull(nameProperties);
        assertEquals(2, nameProperties.size());
        assertLeafs(nameProperties, "first", "last");

        Map<String, Object> address = (Map<String, Object>) typeProperties.get("address");
        assertNotNull(address);
        assertEquals(2, address.size());
        assertTrue(address.containsKey("type"));
        Map<String, Object> addressProperties = (Map<String, Object>) address.get("properties");
        assertNotNull(addressProperties);
        assertEquals(3, addressProperties.size());
        assertLeafs(addressProperties, "street", "location", "area");

        Map<String, Object> properties = (Map<String, Object>) typeProperties.get("properties");
        assertNotNull(properties);
        assertEquals(2, properties.size());
        assertTrue(properties.containsKey("type"));
        Map<String, Object> propertiesProperties = (Map<String, Object>) properties.get("properties");
        assertNotNull(propertiesProperties);
        assertEquals(2, propertiesProperties.size());
        assertMultiField(propertiesProperties, "key", "keyword");
        assertMultiField(propertiesProperties, "value", "keyword");
    }

    @SuppressWarnings("unchecked")
    public static void assertLeafs(Map<String, Object> properties, String... fields) {
        for (String field : fields) {
            assertTrue(properties.containsKey(field));
            Map<String, Object> fieldProp = (Map<String, Object>) properties.get(field);
            assertNotNull(fieldProp);
            assertFalse(fieldProp.containsKey("properties"));
            assertFalse(fieldProp.containsKey("fields"));
        }
    }

    public static void assertMultiField(Map<String, Object> properties, String field, String... subFields) {
        assertTrue(properties.containsKey(field));
        @SuppressWarnings("unchecked")
        Map<String, Object> fieldProp = (Map<String, Object>) properties.get(field);
        assertNotNull(fieldProp);
        assertTrue(fieldProp.containsKey("fields"));
        @SuppressWarnings("unchecked")
        Map<String, Object> subFieldsDef = (Map<String, Object>) fieldProp.get("fields");
        assertLeafs(subFieldsDef, subFields);
    }

    private static final String FIND_MAPPINGS_TEST_ITEM = "{\n"
        + "  \"_doc\": {\n"
        + "      \"_routing\": {\n"
        + "        \"required\":true\n"
        + "      },"
        + "      \"_source\": {\n"
        + "        \"enabled\":false\n"
        + "      },"
        + "      \"properties\": {\n"
        + "        \"name\": {\n"
        + "          \"properties\": {\n"
        + "            \"first\": {\n"
        + "              \"type\": \"keyword\"\n"
        + "            },\n"
        + "            \"last\": {\n"
        + "              \"type\": \"keyword\"\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"birth\": {\n"
        + "          \"type\": \"date\"\n"
        + "        },\n"
        + "        \"age\": {\n"
        + "          \"type\": \"integer\"\n"
        + "        },\n"
        + "        \"ip\": {\n"
        + "          \"type\": \"ip\"\n"
        + "        },\n"
        + "        \"suggest\" : {\n"
        + "          \"type\": \"completion\"\n"
        + "        },\n"
        + "        \"address\": {\n"
        + "          \"type\": \"object\",\n"
        + "          \"properties\": {\n"
        + "            \"street\": {\n"
        + "              \"type\": \"keyword\"\n"
        + "            },\n"
        + "            \"location\": {\n"
        + "              \"type\": \"geo_point\"\n"
        + "            },\n"
        + "            \"area\": {\n"
        + "              \"type\": \"geo_shape\",  \n"
        + "              \"tree\": \"quadtree\",\n"
        + "              \"precision\": \"1m\"\n"
        + "            }\n"
        + "          }\n"
        + "        },\n"
        + "        \"properties\": {\n"
        + "          \"type\": \"nested\",\n"
        + "          \"properties\": {\n"
        + "            \"key\" : {\n"
        + "              \"type\": \"text\",\n"
        + "              \"fields\": {\n"
        + "                \"keyword\" : {\n"
        + "                  \"type\" : \"keyword\"\n"
        + "                }\n"
        + "              }\n"
        + "            },\n"
        + "            \"value\" : {\n"
        + "              \"type\": \"text\",\n"
        + "              \"fields\": {\n"
        + "                \"keyword\" : {\n"
        + "                  \"type\" : \"keyword\"\n"
        + "                }\n"
        + "              }\n"
        + "            }\n"
        + "          }\n"
        + "        }\n"
        + "      }\n"
        + "    }\n"
        + "  }\n"
        + "}";

    public void testTransientSettingsOverridePersistentSettings() {
        final Setting<String> setting = Setting.simpleString("key");
        final Metadata metadata = Metadata.builder()
            .persistentSettings(Settings.builder().put(setting.getKey(), "persistent-value").build())
            .transientSettings(Settings.builder().put(setting.getKey(), "transient-value").build())
            .build();
        assertThat(setting.get(metadata.settings()), equalTo("transient-value"));
    }

    public void testBuilderRejectsNullCustom() {
        final Metadata.Builder builder = Metadata.builder();
        final String key = randomAlphaOfLength(10);
        assertThat(expectThrows(NullPointerException.class, () -> builder.putCustom(key, null)).getMessage(), containsString(key));
    }

    public void testBuilderRejectsNullInCustoms() {
        final Metadata.Builder builder = Metadata.builder();
        final String key = randomAlphaOfLength(10);
        final ImmutableOpenMap.Builder<String, Metadata.Custom> mapBuilder = ImmutableOpenMap.builder();
        mapBuilder.put(key, null);
        final ImmutableOpenMap<String, Metadata.Custom> map = mapBuilder.build();
        assertThat(expectThrows(NullPointerException.class, () -> builder.customs(map)).getMessage(), containsString(key));
    }

    public void testBuilderRejectsDataStreamThatConflictsWithIndex() {
        final String dataStreamName = "my-data-stream";
        IndexMetadata idx = createFirstBackingIndex(dataStreamName).build();
        Metadata.Builder b = Metadata.builder()
            .put(idx, false)
            .put(
                IndexMetadata.builder(dataStreamName).settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1).build(),
                false
            )
            .put(new DataStream(dataStreamName, createTimestampField("@timestamp"), List.of(idx.getIndex())));

        IllegalStateException e = expectThrows(IllegalStateException.class, b::build);
        assertThat(
            e.getMessage(),
            containsString(
                "index, alias, and data stream names need to be unique, but the following duplicates were found [data "
                    + "stream ["
                    + dataStreamName
                    + "] conflicts with index]"
            )
        );
    }

    public void testBuilderRejectsDataStreamThatConflictsWithAlias() {
        final String dataStreamName = "my-data-stream";
        IndexMetadata idx = createFirstBackingIndex(dataStreamName).putAlias(AliasMetadata.builder(dataStreamName).build()).build();
        Metadata.Builder b = Metadata.builder()
            .put(idx, false)
            .put(new DataStream(dataStreamName, createTimestampField("@timestamp"), List.of(idx.getIndex())));

        IllegalStateException e = expectThrows(IllegalStateException.class, b::build);
        assertThat(
            e.getMessage(),
            containsString(
                "index, alias, and data stream names need to be unique, but the following duplicates were found ["
                    + dataStreamName
                    + " (alias of ["
                    + DataStream.getDefaultBackingIndexName(dataStreamName, 1)
                    + "]) conflicts with data stream]"
            )
        );
    }

    public void testBuilderRejectsAliasThatRefersToDataStreamBackingIndex() {
        final String dataStreamName = "my-data-stream";
        final String conflictingName = DataStream.getDefaultBackingIndexName(dataStreamName, 2);
        IndexMetadata idx = createFirstBackingIndex(dataStreamName).putAlias(new AliasMetadata.Builder(conflictingName)).build();
        Metadata.Builder b = Metadata.builder()
            .put(idx, false)
            .put(new DataStream(dataStreamName, createTimestampField("@timestamp"), List.of(idx.getIndex())));

        IllegalStateException e = expectThrows(IllegalStateException.class, b::build);
        assertThat(e.getMessage(), containsString("aliases [" + conflictingName + "] cannot refer to backing indices of data streams"));
    }

    public void testBuilderForDataStreamWithRandomlyNumberedBackingIndices() {
        final String dataStreamName = "my-data-stream";
        final List<Index> backingIndices = new ArrayList<>();
        final int numBackingIndices = randomIntBetween(2, 5);
        int lastBackingIndexNum = 0;
        Metadata.Builder b = Metadata.builder();
        for (int k = 1; k <= numBackingIndices; k++) {
            lastBackingIndexNum = randomIntBetween(lastBackingIndexNum + 1, lastBackingIndexNum + 50);
            IndexMetadata im = IndexMetadata.builder(DataStream.getDefaultBackingIndexName(dataStreamName, lastBackingIndexNum))
                .settings(settings(Version.CURRENT))
                .numberOfShards(1)
                .numberOfReplicas(1)
                .build();
            b.put(im, false);
            backingIndices.add(im.getIndex());
        }

        b.put(new DataStream(dataStreamName, createTimestampField("@timestamp"), backingIndices, lastBackingIndexNum, null));
        Metadata metadata = b.build();
        assertThat(metadata.dataStreams().size(), equalTo(1));
        assertThat(metadata.dataStreams().get(dataStreamName).getName(), equalTo(dataStreamName));
    }

    public void testBuildIndicesLookupForDataStreams() {
        Metadata.Builder b = Metadata.builder();
        int numDataStreams = randomIntBetween(2, 8);
        for (int i = 0; i < numDataStreams; i++) {
            String name = "data-stream-" + i;
            addDataStream(name, b);
        }

        Metadata metadata = b.build();
        assertThat(metadata.dataStreams().size(), equalTo(numDataStreams));
        for (int i = 0; i < numDataStreams; i++) {
            String name = "data-stream-" + i;
            IndexAbstraction value = metadata.getIndicesLookup().get(name);
            assertThat(value, notNullValue());
            DataStream ds = metadata.dataStreams().get(name);
            assertThat(ds, notNullValue());

            assertThat(value.isHidden(), is(false));
            assertThat(value.getType(), equalTo(IndexAbstraction.Type.DATA_STREAM));
            assertThat(value.getIndices().size(), equalTo(ds.getIndices().size()));
            assertThat(value.getWriteIndex().getName(), equalTo(DataStream.getDefaultBackingIndexName(name, ds.getGeneration())));
        }
    }

    public void testBuildIndicesLookupForDataStreamAliases() {
        Metadata.Builder b = Metadata.builder();

        addDataStream("d1", b);
        addDataStream("d2", b);
        addDataStream("d3", b);
        addDataStream("d4", b);

        b.put("a1", "d1", null, null);
        b.put("a1", "d2", null, null);
        b.put("a2", "d3", null, null);
        b.put("a3", "d1", null, null);

        Metadata metadata = b.build();
        assertThat(metadata.dataStreams().size(), equalTo(4));
        IndexAbstraction value = metadata.getIndicesLookup().get("d1");
        assertThat(value, notNullValue());
        assertThat(value.getType(), equalTo(IndexAbstraction.Type.DATA_STREAM));
        assertThat(value.getAliases(), containsInAnyOrder("a1", "a3"));

        value = metadata.getIndicesLookup().get("d2");
        assertThat(value, notNullValue());
        assertThat(value.getType(), equalTo(IndexAbstraction.Type.DATA_STREAM));
        assertThat(value.getAliases(), contains("a1"));

        value = metadata.getIndicesLookup().get("d3");
        assertThat(value, notNullValue());
        assertThat(value.getType(), equalTo(IndexAbstraction.Type.DATA_STREAM));
        assertThat(value.getAliases(), contains("a2"));

        value = metadata.getIndicesLookup().get("d4");
        assertThat(value, notNullValue());
        assertThat(value.getType(), equalTo(IndexAbstraction.Type.DATA_STREAM));
        assertThat(value.getAliases(), empty());

        value = metadata.getIndicesLookup().get("a1");
        assertThat(value, notNullValue());
        assertThat(value.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(value.getAliases(), nullValue());

        value = metadata.getIndicesLookup().get("a2");
        assertThat(value, notNullValue());
        assertThat(value.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(value.getAliases(), nullValue());

        value = metadata.getIndicesLookup().get("a3");
        assertThat(value, notNullValue());
        assertThat(value.getType(), equalTo(IndexAbstraction.Type.ALIAS));
        assertThat(value.getAliases(), nullValue());
    }

    private void addDataStream(String name, Metadata.Builder b) {
        int numBackingIndices = randomIntBetween(1, 4);
        List<Index> indices = new ArrayList<>(numBackingIndices);
        for (int j = 1; j <= numBackingIndices; j++) {
            IndexMetadata idx = createBackingIndex(name, j).build();
            indices.add(idx.getIndex());
            b.put(idx, true);
        }
        b.put(new DataStream(name, createTimestampField("@timestamp"), indices));
    }

    public void testIndicesLookupRecordsDataStreamForBackingIndices() {
        final int numIndices = randomIntBetween(2, 5);
        final int numBackingIndices = randomIntBetween(2, 5);
        final String dataStreamName = "my-data-stream";
        CreateIndexResult result = createIndices(numIndices, numBackingIndices, dataStreamName);

        SortedMap<String, IndexAbstraction> indicesLookup = result.metadata.getIndicesLookup();
        assertThat(indicesLookup.size(), equalTo(result.indices.size() + result.backingIndices.size() + 1));
        for (Index index : result.indices) {
            assertTrue(indicesLookup.containsKey(index.getName()));
            assertNull(indicesLookup.get(index.getName()).getParentDataStream());
        }
        for (Index index : result.backingIndices) {
            assertTrue(indicesLookup.containsKey(index.getName()));
            assertNotNull(indicesLookup.get(index.getName()).getParentDataStream());
            assertThat(indicesLookup.get(index.getName()).getParentDataStream().getName(), equalTo(dataStreamName));
        }
    }

    public void testSerialization() throws IOException {
        final Metadata orig = randomMetadata();
        final BytesStreamOutput out = new BytesStreamOutput();
        orig.writeTo(out);
        NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(ClusterModule.getNamedWriteables());
        final Metadata fromStreamMeta = Metadata.readFrom(
            new NamedWriteableAwareStreamInput(out.bytes().streamInput(), namedWriteableRegistry)
        );
        assertTrue(Metadata.isGlobalStateEquals(orig, fromStreamMeta));
    }

    public void testValidateDataStreamsNoConflicts() {
        Metadata metadata = createIndices(5, 10, "foo-datastream").metadata;
        // don't expect any exception when validating a system without indices that would conflict with future backing indices
        validateDataStreams(metadata.getIndicesLookup(), (DataStreamMetadata) metadata.customs().get(DataStreamMetadata.TYPE));
    }

    public void testValidateDataStreamsIgnoresIndicesWithoutCounter() {
        String dataStreamName = "foo-datastream";
        Metadata metadata = Metadata.builder(createIndices(10, 10, dataStreamName).metadata)
            .put(
                new IndexMetadata.Builder(dataStreamName + "-index-without-counter").settings(settings(Version.CURRENT))
                    .numberOfShards(1)
                    .numberOfReplicas(1)
            )
            .put(
                new IndexMetadata.Builder(dataStreamName + randomAlphaOfLength(10)).settings(settings(Version.CURRENT))
                    .numberOfShards(1)
                    .numberOfReplicas(1)

            )
            .put(
                new IndexMetadata.Builder(randomAlphaOfLength(10)).settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1)

            )
            .build();
        // don't expect any exception when validating against non-backing indices that don't conform to the backing indices naming
        // convention
        validateDataStreams(metadata.getIndicesLookup(), (DataStreamMetadata) metadata.customs().get(DataStreamMetadata.TYPE));
    }

    public void testValidateDataStreamsAllowsNamesThatStartsWithPrefix() {
        String dataStreamName = "foo-datastream";
        Metadata metadata = Metadata.builder(createIndices(10, 10, dataStreamName).metadata)
            .put(
                new IndexMetadata.Builder(DataStream.BACKING_INDEX_PREFIX + dataStreamName + "-something-100012").settings(
                    settings(Version.CURRENT)
                ).numberOfShards(1).numberOfReplicas(1)
            )
            .build();
        // don't expect any exception when validating against (potentially backing) indices that can't create conflict because of
        // additional text before number
        validateDataStreams(metadata.getIndicesLookup(), (DataStreamMetadata) metadata.customs().get(DataStreamMetadata.TYPE));
    }

    public void testValidateDataStreamsAllowsPrefixedBackingIndices() {
        String dataStreamName = "foo-datastream";
        int generations = 10;
        List<IndexMetadata> backingIndices = new ArrayList<>(generations);
        for (int i = 1; i <= generations; i++) {
            IndexMetadata idx;
            if (i % 2 == 0 && i < generations) {
                idx = IndexMetadata.builder("shrink-" + DataStream.getDefaultBackingIndexName(dataStreamName, i))
                    .settings(ESTestCase.settings(Version.CURRENT).put("index.hidden", true))
                    .numberOfShards(1)
                    .numberOfReplicas(1)
                    .build();
            } else {
                idx = createBackingIndex(dataStreamName, i).build();
            }
            backingIndices.add(idx);
        }
        DataStream dataStream = new DataStream(
            dataStreamName,
            createTimestampField("@timestamp"),
            backingIndices.stream().map(IndexMetadata::getIndex).collect(Collectors.toList())
        );

        IndexAbstraction.DataStream dataStreamAbstraction = new IndexAbstraction.DataStream(dataStream, List.of());
        // manually building the indices lookup as going through Metadata.Builder#build would trigger the validate method already
        SortedMap<String, IndexAbstraction> indicesLookup = new TreeMap<>();
        for (IndexMetadata indexMeta : backingIndices) {
            indicesLookup.put(indexMeta.getIndex().getName(), new IndexAbstraction.ConcreteIndex(indexMeta, dataStreamAbstraction));
        }

        for (int i = 1; i <= generations; i++) {
            // for the indices that we added in the data stream with a "shrink-" prefix, add the non-prefixed indices to the lookup
            if (i % 2 == 0 && i < generations) {
                IndexMetadata indexMeta = createBackingIndex(dataStreamName, i).build();
                indicesLookup.put(indexMeta.getIndex().getName(), new IndexAbstraction.ConcreteIndex(indexMeta, dataStreamAbstraction));
            }
        }
        DataStreamMetadata dataStreamMetadata = new DataStreamMetadata(Map.of(dataStreamName, dataStream), Map.of());

        // prefixed indices with a lower generation than the data stream's generation are allowed even if the non-prefixed, matching the
        // data stream backing indices naming pattern, indices are already in the system
        validateDataStreams(indicesLookup, dataStreamMetadata);
    }

    public void testValidateDataStreamsForNullDataStreamMetadata() {
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("foo-index").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();

        try {
            validateDataStreams(metadata.getIndicesLookup(), null);
        } catch (Exception e) {
            fail("did not expect exception when validating a system without any data streams but got " + e.getMessage());
        }
    }

    /**
     * Tests for the implementation of data stream snapshot reconciliation are located in {@link DataStreamTests#testSnapshot()}
     */
    public void testSnapshot() {
        var postSnapshotMetadata = randomMetadata(randomIntBetween(1, 5));
        var dataStreamsToSnapshot = randomSubsetOf(new ArrayList<>(postSnapshotMetadata.dataStreams().keySet()));
        List<String> indicesInSnapshot = new ArrayList<>();
        for (var dsName : dataStreamsToSnapshot) {
            // always include at least one backing index per data stream
            DataStream ds = postSnapshotMetadata.dataStreams().get(dsName);
            indicesInSnapshot.addAll(
                randomSubsetOf(
                    randomIntBetween(1, ds.getIndices().size()),
                    ds.getIndices().stream().map(Index::getName).collect(Collectors.toList())
                )
            );
        }
        var reconciledMetadata = Metadata.snapshot(postSnapshotMetadata, dataStreamsToSnapshot, indicesInSnapshot);
        assertThat(reconciledMetadata.dataStreams().size(), equalTo(postSnapshotMetadata.dataStreams().size()));
        for (DataStream ds : reconciledMetadata.dataStreams().values()) {
            assertThat(ds.getIndices().size(), greaterThanOrEqualTo(1));
        }
    }

    public void testSnapshotWithMissingDataStream() {
        var postSnapshotMetadata = randomMetadata(randomIntBetween(1, 5));
        var dataStreamsToSnapshot = randomSubsetOf(new ArrayList<>(postSnapshotMetadata.dataStreams().keySet()));
        List<String> indicesInSnapshot = new ArrayList<>();
        for (var dsName : dataStreamsToSnapshot) {
            // always include at least one backing index per data stream
            DataStream ds = postSnapshotMetadata.dataStreams().get(dsName);
            indicesInSnapshot.addAll(
                randomSubsetOf(
                    randomIntBetween(1, ds.getIndices().size()),
                    ds.getIndices().stream().map(Index::getName).collect(Collectors.toList())
                )
            );
        }
        String missingDataStream = randomAlphaOfLength(5).toLowerCase(Locale.ROOT);
        dataStreamsToSnapshot.add(missingDataStream);
        IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> Metadata.snapshot(postSnapshotMetadata, dataStreamsToSnapshot, indicesInSnapshot)
        );
        assertThat(e.getMessage(), containsString("unable to find data stream [" + missingDataStream + "]"));
    }

    public void testDataStreamAliases() {
        Metadata.Builder mdBuilder = Metadata.builder();

        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-eu"));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-eu", null, null), is(true));
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-us"));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-us", null, null), is(true));
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-au"));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-au", null, null), is(true));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-au", null, null), is(false));

        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-eu", "logs-postgres-us", "logs-postgres-au")
        );
    }

    public void testDataStreamReferToNonExistingDataStream() {
        Metadata.Builder mdBuilder = Metadata.builder();

        Exception e = expectThrows(IllegalArgumentException.class, () -> mdBuilder.put("logs-postgres", "logs-postgres-eu", null, null));
        assertThat(e.getMessage(), equalTo("alias [logs-postgres] refers to a non existing data stream [logs-postgres-eu]"));

        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-eu"));
        mdBuilder.put("logs-postgres", "logs-postgres-eu", null, null);
        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-eu"));
    }

    public void testDeleteDataStreamShouldUpdateAlias() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-eu"));
        mdBuilder.put("logs-postgres", "logs-postgres-eu", null, null);
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-us"));
        mdBuilder.put("logs-postgres", "logs-postgres-us", null, null);
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-au"));
        mdBuilder.put("logs-postgres", "logs-postgres-au", null, null);
        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-eu", "logs-postgres-us", "logs-postgres-au")
        );

        mdBuilder = Metadata.builder(metadata);
        mdBuilder.removeDataStream("logs-postgres-us");
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-eu", "logs-postgres-au")
        );

        mdBuilder = Metadata.builder(metadata);
        mdBuilder.removeDataStream("logs-postgres-au");
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-eu"));

        mdBuilder = Metadata.builder(metadata);
        mdBuilder.removeDataStream("logs-postgres-eu");
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), nullValue());
    }

    public void testDeleteDataStreamAlias() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-eu"));
        mdBuilder.put("logs-postgres", "logs-postgres-eu", null, null);
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-us"));
        mdBuilder.put("logs-postgres", "logs-postgres-us", null, null);
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-au"));
        mdBuilder.put("logs-postgres", "logs-postgres-au", null, null);
        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-eu", "logs-postgres-us", "logs-postgres-au")
        );

        mdBuilder = Metadata.builder(metadata);
        assertThat(mdBuilder.removeDataStreamAlias("logs-postgres", "logs-postgres-us", true), is(true));
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-eu", "logs-postgres-au")
        );

        mdBuilder = Metadata.builder(metadata);
        assertThat(mdBuilder.removeDataStreamAlias("logs-postgres", "logs-postgres-au", true), is(true));
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-eu"));

        mdBuilder = Metadata.builder(metadata);
        assertThat(mdBuilder.removeDataStreamAlias("logs-postgres", "logs-postgres-eu", true), is(true));
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), nullValue());
    }

    public void testDeleteDataStreamAliasMustExists() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-eu"));
        mdBuilder.put("logs-postgres", "logs-postgres-eu", null, null);
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-us"));
        mdBuilder.put("logs-postgres", "logs-postgres-us", null, null);
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-au"));
        mdBuilder.put("logs-postgres", "logs-postgres-au", null, null);
        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-eu", "logs-postgres-us", "logs-postgres-au")
        );

        Metadata.Builder mdBuilder2 = Metadata.builder(metadata);
        expectThrows(ResourceNotFoundException.class, () -> mdBuilder2.removeDataStreamAlias("logs-mysql", "logs-postgres-us", true));
        assertThat(mdBuilder2.removeDataStreamAlias("logs-mysql", "logs-postgres-us", false), is(false));
    }

    public void testDataStreamWriteAlias() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-replicated"));
        mdBuilder.put("logs-postgres", "logs-postgres-replicated", null, null);

        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), nullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-replicated"));

        mdBuilder = Metadata.builder(metadata);
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-replicated", true, null), is(true));

        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), equalTo("logs-postgres-replicated"));
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-replicated"));
    }

    public void testDataStreamMultipleWriteAlias() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-foobar"));
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-barbaz"));
        mdBuilder.put("logs", "logs-foobar", true, null);
        mdBuilder.put("logs", "logs-barbaz", true, null);

        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs").getWriteDataStream(), equalTo("logs-barbaz"));
        assertThat(metadata.dataStreamAliases().get("logs").getDataStreams(), containsInAnyOrder("logs-foobar", "logs-barbaz"));
    }

    public void testDataStreamWriteAliasUnset() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-replicated"));
        mdBuilder.put("logs-postgres", "logs-postgres-replicated", true, null);

        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), equalTo("logs-postgres-replicated"));
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-replicated"));

        mdBuilder = Metadata.builder(metadata);
        // Side check: null value isn't changing anything:
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-replicated", null, null), is(false));
        // Unset write flag
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-replicated", false, null), is(true));
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), nullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-replicated"));
    }

    public void testDataStreamWriteAliasChange() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-primary"));
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-replicated"));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-primary", true, null), is(true));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-replicated", null, null), is(true));

        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), equalTo("logs-postgres-primary"));
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-primary", "logs-postgres-replicated")
        );

        // change write flag:
        mdBuilder = Metadata.builder(metadata);
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-primary", false, null), is(true));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-replicated", true, null), is(true));
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), equalTo("logs-postgres-replicated"));
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-primary", "logs-postgres-replicated")
        );
    }

    public void testDataStreamWriteRemoveAlias() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-primary"));
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-replicated"));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-primary", true, null), is(true));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-replicated", null, null), is(true));

        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), equalTo("logs-postgres-primary"));
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-primary", "logs-postgres-replicated")
        );

        mdBuilder = Metadata.builder(metadata);
        assertThat(mdBuilder.removeDataStreamAlias("logs-postgres", "logs-postgres-primary", randomBoolean()), is(true));
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), nullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-replicated"));
    }

    public void testDataStreamWriteRemoveDataStream() {
        Metadata.Builder mdBuilder = Metadata.builder();
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-primary"));
        mdBuilder.put(DataStreamTestHelper.randomInstance("logs-postgres-replicated"));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-primary", true, null), is(true));
        assertThat(mdBuilder.put("logs-postgres", "logs-postgres-replicated", null, null), is(true));

        Metadata metadata = mdBuilder.build();
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), equalTo("logs-postgres-primary"));
        assertThat(
            metadata.dataStreamAliases().get("logs-postgres").getDataStreams(),
            containsInAnyOrder("logs-postgres-primary", "logs-postgres-replicated")
        );

        mdBuilder = Metadata.builder(metadata);
        mdBuilder.removeDataStream("logs-postgres-primary");
        metadata = mdBuilder.build();
        assertThat(metadata.dataStreams().keySet(), contains("logs-postgres-replicated"));
        assertThat(metadata.dataStreamAliases().get("logs-postgres"), notNullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getWriteDataStream(), nullValue());
        assertThat(metadata.dataStreamAliases().get("logs-postgres").getDataStreams(), containsInAnyOrder("logs-postgres-replicated"));
    }

    public void testReuseIndicesLookup() {
        String indexName = "my-index";
        String aliasName = "my-alias";
        String dataStreamName = "logs-mysql-prod";
        String dataStreamAliasName = "logs-mysql";
        Metadata previous = Metadata.builder().build();

        // Things that should change indices lookup
        {
            Metadata.Builder builder = Metadata.builder(previous);
            IndexMetadata idx = DataStreamTestHelper.createFirstBackingIndex(dataStreamName).build();
            builder.put(idx, true);
            DataStream dataStream = new DataStream(dataStreamName, new DataStream.TimestampField("@timestamp"), List.of(idx.getIndex()));
            builder.put(dataStream);
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), not(sameInstance(metadata.getIndicesLookup())));
            previous = metadata;
        }
        {
            Metadata.Builder builder = Metadata.builder(previous);
            builder.put(dataStreamAliasName, dataStreamName, false, null);
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), not(sameInstance(metadata.getIndicesLookup())));
            previous = metadata;
        }
        {
            Metadata.Builder builder = Metadata.builder(previous);
            builder.put(dataStreamAliasName, dataStreamName, true, null);
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), not(sameInstance(metadata.getIndicesLookup())));
            previous = metadata;
        }
        {
            Metadata.Builder builder = Metadata.builder(previous);
            builder.put(
                IndexMetadata.builder(indexName)
                    .settings(settings(Version.CURRENT))
                    .creationDate(randomNonNegativeLong())
                    .numberOfShards(1)
                    .numberOfReplicas(0)
            );
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), not(sameInstance(metadata.getIndicesLookup())));
            previous = metadata;
        }
        {
            Metadata.Builder builder = Metadata.builder(previous);
            IndexMetadata.Builder imBuilder = IndexMetadata.builder(builder.get(indexName));
            imBuilder.putAlias(AliasMetadata.builder(aliasName).build());
            builder.put(imBuilder);
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), not(sameInstance(metadata.getIndicesLookup())));
            previous = metadata;
        }
        {
            Metadata.Builder builder = Metadata.builder(previous);
            IndexMetadata.Builder imBuilder = IndexMetadata.builder(builder.get(indexName));
            imBuilder.putAlias(AliasMetadata.builder(aliasName).writeIndex(true).build());
            builder.put(imBuilder);
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), not(sameInstance(metadata.getIndicesLookup())));
            previous = metadata;
        }
        {
            Metadata.Builder builder = Metadata.builder(previous);
            IndexMetadata.Builder imBuilder = IndexMetadata.builder(builder.get(indexName));
            Settings.Builder sBuilder = Settings.builder()
                .put(builder.get(indexName).getSettings())
                .put(IndexMetadata.INDEX_HIDDEN_SETTING.getKey(), true);
            imBuilder.settings(sBuilder.build());
            builder.put(imBuilder);
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), not(sameInstance(metadata.getIndicesLookup())));
            previous = metadata;
        }

        // Things that shouldn't change indices lookup
        {
            Metadata.Builder builder = Metadata.builder(previous);
            IndexMetadata.Builder imBuilder = IndexMetadata.builder(builder.get(indexName));
            imBuilder.numberOfReplicas(2);
            builder.put(imBuilder);
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), sameInstance(metadata.getIndicesLookup()));
            previous = metadata;
        }
        {
            Metadata.Builder builder = Metadata.builder(previous);
            IndexMetadata.Builder imBuilder = IndexMetadata.builder(builder.get(indexName));
            Settings.Builder sBuilder = Settings.builder()
                .put(builder.get(indexName).getSettings())
                .put(IndexSettings.DEFAULT_FIELD_SETTING.getKey(), "val");
            imBuilder.settings(sBuilder.build());
            builder.put(imBuilder);
            Metadata metadata = builder.build();
            assertThat(previous.getIndicesLookup(), sameInstance(metadata.getIndicesLookup()));
            previous = metadata;
        }
    }

    public static final String SYSTEM_ALIAS_NAME = "system_alias";

    public void testHiddenAliasValidation() {
        final String hiddenAliasName = "hidden_alias";

        IndexMetadata hidden1 = buildIndexWithAlias("hidden1", hiddenAliasName, true, Version.CURRENT, false);
        IndexMetadata hidden2 = buildIndexWithAlias("hidden2", hiddenAliasName, true, Version.CURRENT, false);
        IndexMetadata hidden3 = buildIndexWithAlias("hidden3", hiddenAliasName, true, Version.CURRENT, false);

        IndexMetadata nonHidden = buildIndexWithAlias("nonhidden1", hiddenAliasName, false, Version.CURRENT, false);
        IndexMetadata unspecified = buildIndexWithAlias("nonhidden2", hiddenAliasName, null, Version.CURRENT, false);

        {
            // Should be ok:
            metadataWithIndices(hidden1, hidden2, hidden3);
        }

        {
            // Should be ok:
            if (randomBoolean()) {
                metadataWithIndices(nonHidden, unspecified);
            } else {
                metadataWithIndices(unspecified, nonHidden);
            }
        }

        {
            IllegalStateException exception = expectThrows(
                IllegalStateException.class,
                () -> metadataWithIndices(hidden1, hidden2, hidden3, nonHidden)
            );
            assertThat(exception.getMessage(), containsString("alias [" + hiddenAliasName + "] has is_hidden set to true on indices ["));
            assertThat(
                exception.getMessage(),
                allOf(
                    containsString(hidden1.getIndex().getName()),
                    containsString(hidden2.getIndex().getName()),
                    containsString(hidden3.getIndex().getName())
                )
            );
            assertThat(
                exception.getMessage(),
                containsString(
                    "but does not have is_hidden set to true on indices ["
                        + nonHidden.getIndex().getName()
                        + "]; alias must have the same is_hidden setting on all indices"
                )
            );
        }

        {
            IllegalStateException exception = expectThrows(
                IllegalStateException.class,
                () -> metadataWithIndices(hidden1, hidden2, hidden3, unspecified)
            );
            assertThat(exception.getMessage(), containsString("alias [" + hiddenAliasName + "] has is_hidden set to true on indices ["));
            assertThat(
                exception.getMessage(),
                allOf(
                    containsString(hidden1.getIndex().getName()),
                    containsString(hidden2.getIndex().getName()),
                    containsString(hidden3.getIndex().getName())
                )
            );
            assertThat(
                exception.getMessage(),
                containsString(
                    "but does not have is_hidden set to true on indices ["
                        + unspecified.getIndex().getName()
                        + "]; alias must have the same is_hidden setting on all indices"
                )
            );
        }

        {
            final IndexMetadata hiddenIndex = randomFrom(hidden1, hidden2, hidden3);
            IllegalStateException exception = expectThrows(IllegalStateException.class, () -> {
                if (randomBoolean()) {
                    metadataWithIndices(nonHidden, unspecified, hiddenIndex);
                } else {
                    metadataWithIndices(unspecified, nonHidden, hiddenIndex);
                }
            });
            assertThat(
                exception.getMessage(),
                containsString(
                    "alias ["
                        + hiddenAliasName
                        + "] has is_hidden set to true on "
                        + "indices ["
                        + hiddenIndex.getIndex().getName()
                        + "] but does not have is_hidden set to true on indices ["
                )
            );
            assertThat(
                exception.getMessage(),
                allOf(containsString(unspecified.getIndex().getName()), containsString(nonHidden.getIndex().getName()))
            );
            assertThat(exception.getMessage(), containsString("but does not have is_hidden set to true on indices ["));
        }
    }

    public void testSystemAliasValidationMixedVersionSystemAndRegularFails() {
        final Version random7xVersion = VersionUtils.randomVersionBetween(
            random(),
            Version.V_7_0_0,
            VersionUtils.getPreviousVersion(Version.V_8_0_0)
        );
        final IndexMetadata currentVersionSystem = buildIndexWithAlias(".system1", SYSTEM_ALIAS_NAME, null, Version.CURRENT, true);
        final IndexMetadata oldVersionSystem = buildIndexWithAlias(".oldVersionSystem", SYSTEM_ALIAS_NAME, null, random7xVersion, true);
        final IndexMetadata regularIndex = buildIndexWithAlias("regular1", SYSTEM_ALIAS_NAME, false, Version.CURRENT, false);

        IllegalStateException exception = expectThrows(
            IllegalStateException.class,
            () -> metadataWithIndices(currentVersionSystem, oldVersionSystem, regularIndex)
        );
        assertThat(
            exception.getMessage(),
            containsString(
                "alias ["
                    + SYSTEM_ALIAS_NAME
                    + "] refers to both system indices ["
                    + currentVersionSystem.getIndex().getName()
                    + "] and non-system indices: ["
                    + regularIndex.getIndex().getName()
                    + "], but aliases must refer to either system or non-system indices, not both"
            )
        );
    }

    public void testSystemAliasValidationNewSystemAndRegularFails() {
        final IndexMetadata currentVersionSystem = buildIndexWithAlias(".system1", SYSTEM_ALIAS_NAME, null, Version.CURRENT, true);
        final IndexMetadata regularIndex = buildIndexWithAlias("regular1", SYSTEM_ALIAS_NAME, false, Version.CURRENT, false);

        IllegalStateException exception = expectThrows(
            IllegalStateException.class,
            () -> metadataWithIndices(currentVersionSystem, regularIndex)
        );
        assertThat(
            exception.getMessage(),
            containsString(
                "alias ["
                    + SYSTEM_ALIAS_NAME
                    + "] refers to both system indices ["
                    + currentVersionSystem.getIndex().getName()
                    + "] and non-system indices: ["
                    + regularIndex.getIndex().getName()
                    + "], but aliases must refer to either system or non-system indices, not both"
            )
        );
    }

    public void testSystemAliasOldSystemAndNewRegular() {
        final Version random7xVersion = VersionUtils.randomVersionBetween(
            random(),
            Version.V_7_0_0,
            VersionUtils.getPreviousVersion(Version.V_8_0_0)
        );
        final IndexMetadata oldVersionSystem = buildIndexWithAlias(".oldVersionSystem", SYSTEM_ALIAS_NAME, null, random7xVersion, true);
        final IndexMetadata regularIndex = buildIndexWithAlias("regular1", SYSTEM_ALIAS_NAME, false, Version.CURRENT, false);

        // Should be ok:
        metadataWithIndices(oldVersionSystem, regularIndex);
    }

    public void testSystemIndexValidationAllRegular() {
        final Version random7xVersion = VersionUtils.randomVersionBetween(
            random(),
            Version.V_7_0_0,
            VersionUtils.getPreviousVersion(Version.V_8_0_0)
        );
        final IndexMetadata currentVersionSystem = buildIndexWithAlias(".system1", SYSTEM_ALIAS_NAME, null, Version.CURRENT, true);
        final IndexMetadata currentVersionSystem2 = buildIndexWithAlias(".system2", SYSTEM_ALIAS_NAME, null, Version.CURRENT, true);
        final IndexMetadata oldVersionSystem = buildIndexWithAlias(".oldVersionSystem", SYSTEM_ALIAS_NAME, null, random7xVersion, true);

        // Should be ok
        metadataWithIndices(currentVersionSystem, currentVersionSystem2, oldVersionSystem);
    }

    public void testSystemAliasValidationAllSystemSomeOld() {
        final Version random7xVersion = VersionUtils.randomVersionBetween(
            random(),
            Version.V_7_0_0,
            VersionUtils.getPreviousVersion(Version.V_8_0_0)
        );
        final IndexMetadata currentVersionSystem = buildIndexWithAlias(".system1", SYSTEM_ALIAS_NAME, null, Version.CURRENT, true);
        final IndexMetadata currentVersionSystem2 = buildIndexWithAlias(".system2", SYSTEM_ALIAS_NAME, null, Version.CURRENT, true);
        final IndexMetadata oldVersionSystem = buildIndexWithAlias(".oldVersionSystem", SYSTEM_ALIAS_NAME, null, random7xVersion, true);

        // Should be ok:
        metadataWithIndices(currentVersionSystem, currentVersionSystem2, oldVersionSystem);
    }

    public void testSystemAliasValidationAll8x() {
        final IndexMetadata currentVersionSystem = buildIndexWithAlias(".system1", SYSTEM_ALIAS_NAME, null, Version.CURRENT, true);
        final IndexMetadata currentVersionSystem2 = buildIndexWithAlias(".system2", SYSTEM_ALIAS_NAME, null, Version.CURRENT, true);

        // Should be ok
        metadataWithIndices(currentVersionSystem, currentVersionSystem2);
    }

    private void metadataWithIndices(IndexMetadata... indices) {
        Metadata.Builder builder = Metadata.builder();
        for (var cursor : indices) {
            builder.put(cursor, false);
        }
        builder.build();
    }

    private IndexMetadata buildIndexWithAlias(
        String indexName,
        String aliasName,
        @Nullable Boolean aliasIsHidden,
        Version indexCreationVersion,
        boolean isSystem
    ) {
        final AliasMetadata.Builder aliasMetadata = new AliasMetadata.Builder(aliasName);
        if (aliasIsHidden != null || randomBoolean()) {
            aliasMetadata.isHidden(aliasIsHidden);
        }
        return new IndexMetadata.Builder(indexName).settings(settings(indexCreationVersion))
            .system(isSystem)
            .numberOfShards(1)
            .numberOfReplicas(0)
            .putAlias(aliasMetadata)
            .build();
    }

    public void testMappingDuplication() {
        final Set<String> randomMappingDefinitions;
        {
            int numEntries = randomIntBetween(4, 8);
            randomMappingDefinitions = new HashSet<>(numEntries);
            for (int i = 0; i < numEntries; i++) {
                Map<String, Object> mapping = RandomAliasActionsGenerator.randomMap(2);
                String mappingAsString = Strings.toString((builder, params) -> builder.mapContents(mapping));
                randomMappingDefinitions.add(mappingAsString);
            }
        }

        Metadata metadata;
        int numIndices = randomIntBetween(16, 32);
        {
            String[] definitions = randomMappingDefinitions.toArray(String[]::new);
            Metadata.Builder mb = new Metadata.Builder();
            for (int i = 0; i < numIndices; i++) {
                IndexMetadata.Builder indexBuilder = IndexMetadata.builder("index-" + i)
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putMapping(definitions[i % randomMappingDefinitions.size()])
                    .numberOfShards(1)
                    .numberOfReplicas(0);
                if (randomBoolean()) {
                    mb.put(indexBuilder);
                } else {
                    mb.put(indexBuilder.build(), true);
                }
            }
            metadata = mb.build();
        }
        assertThat(metadata.getMappingsByHash().size(), equalTo(randomMappingDefinitions.size()));
        assertThat(
            metadata.indices().stream().map(entry -> entry.getValue().mapping()).collect(Collectors.toSet()),
            hasSize(metadata.getMappingsByHash().size())
        );

        // Add a new index with a new index with known mapping:
        MappingMetadata mapping = metadata.indices().get("index-" + randomInt(numIndices - 1)).mapping();
        MappingMetadata entry = metadata.getMappingsByHash().get(mapping.getSha256());
        {
            Metadata.Builder mb = new Metadata.Builder(metadata);
            mb.put(
                IndexMetadata.builder("index-" + numIndices)
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putMapping(mapping)
                    .numberOfShards(1)
                    .numberOfReplicas(0)
            );
            metadata = mb.build();
        }
        assertThat(metadata.getMappingsByHash().size(), equalTo(randomMappingDefinitions.size()));
        assertThat(metadata.getMappingsByHash().get(mapping.getSha256()), equalTo(entry));

        // Remove index and ensure mapping cache stays the same
        {
            Metadata.Builder mb = new Metadata.Builder(metadata);
            mb.remove("index-" + numIndices);
            metadata = mb.build();
        }
        assertThat(metadata.getMappingsByHash().size(), equalTo(randomMappingDefinitions.size()));
        assertThat(metadata.getMappingsByHash().get(mapping.getSha256()), equalTo(entry));

        // Update a mapping of an index:
        IndexMetadata luckyIndex = metadata.index("index-" + randomInt(numIndices - 1));
        entry = metadata.getMappingsByHash().get(luckyIndex.mapping().getSha256());
        MappingMetadata updatedMapping = new MappingMetadata(MapperService.SINGLE_MAPPING_NAME, Map.of("mapping", "updated"));
        {
            Metadata.Builder mb = new Metadata.Builder(metadata);
            mb.put(IndexMetadata.builder(luckyIndex).putMapping(updatedMapping));
            metadata = mb.build();
        }
        assertThat(metadata.getMappingsByHash().size(), equalTo(randomMappingDefinitions.size() + 1));
        assertThat(metadata.getMappingsByHash().get(luckyIndex.mapping().getSha256()), equalTo(entry));
        assertThat(metadata.getMappingsByHash().get(updatedMapping.getSha256()), equalTo(updatedMapping));

        // Remove the index with updated mapping
        {
            Metadata.Builder mb = new Metadata.Builder(metadata);
            mb.remove(luckyIndex.getIndex().getName());
            metadata = mb.build();
        }
        assertThat(metadata.getMappingsByHash().size(), equalTo(randomMappingDefinitions.size()));
        assertThat(metadata.getMappingsByHash().get(updatedMapping.getSha256()), nullValue());

        // Add an index with new mapping and then later remove it:
        MappingMetadata newMapping = new MappingMetadata(MapperService.SINGLE_MAPPING_NAME, Map.of("new", "mapping"));
        {
            Metadata.Builder mb = new Metadata.Builder(metadata);
            mb.put(
                IndexMetadata.builder("index-" + numIndices)
                    .settings(Settings.builder().put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT))
                    .putMapping(newMapping)
                    .numberOfShards(1)
                    .numberOfReplicas(0)
            );
            metadata = mb.build();
        }
        assertThat(metadata.getMappingsByHash().size(), equalTo(randomMappingDefinitions.size() + 1));
        assertThat(metadata.getMappingsByHash().get(newMapping.getSha256()), equalTo(newMapping));

        {
            Metadata.Builder mb = new Metadata.Builder(metadata);
            mb.remove("index-" + numIndices);
            metadata = mb.build();
        }
        assertThat(metadata.getMappingsByHash().size(), equalTo(randomMappingDefinitions.size()));
        assertThat(metadata.getMappingsByHash().get(newMapping.getSha256()), nullValue());
    }

    public static Metadata randomMetadata() {
        return randomMetadata(1);
    }

    public static Metadata randomMetadata(int numDataStreams) {
        Metadata.Builder md = Metadata.builder()
            .put(buildIndexMetadata("index", "alias", randomBoolean() ? null : randomBoolean()).build(), randomBoolean())
            .put(
                IndexTemplateMetadata.builder("template" + randomAlphaOfLength(3))
                    .patterns(Arrays.asList("bar-*", "foo-*"))
                    .settings(Settings.builder().put("random_index_setting_" + randomAlphaOfLength(3), randomAlphaOfLength(5)).build())
                    .build()
            )
            .persistentSettings(Settings.builder().put("setting" + randomAlphaOfLength(3), randomAlphaOfLength(4)).build())
            .transientSettings(Settings.builder().put("other_setting" + randomAlphaOfLength(3), randomAlphaOfLength(4)).build())
            .clusterUUID("uuid" + randomAlphaOfLength(3))
            .clusterUUIDCommitted(randomBoolean())
            .indexGraveyard(IndexGraveyardTests.createRandom())
            .version(randomNonNegativeLong())
            .put("component_template_" + randomAlphaOfLength(3), ComponentTemplateTests.randomInstance())
            .put("index_template_v2_" + randomAlphaOfLength(3), ComposableIndexTemplateTests.randomInstance());

        for (int k = 0; k < numDataStreams; k++) {
            DataStream randomDataStream = DataStreamTestHelper.randomInstance();
            for (Index index : randomDataStream.getIndices()) {
                md.put(DataStreamTestHelper.getIndexMetadataBuilderForIndex(index));
            }
            md.put(randomDataStream);
        }

        return md.build();
    }

    private static CreateIndexResult createIndices(int numIndices, int numBackingIndices, String dataStreamName) {
        // create some indices that do not back a data stream
        final List<Index> indices = new ArrayList<>();
        int lastIndexNum = randomIntBetween(9, 50);
        Metadata.Builder b = Metadata.builder();
        for (int k = 1; k <= numIndices; k++) {
            IndexMetadata im = IndexMetadata.builder(DataStream.getDefaultBackingIndexName("index", lastIndexNum))
                .settings(settings(Version.CURRENT))
                .numberOfShards(1)
                .numberOfReplicas(1)
                .build();
            b.put(im, false);
            indices.add(im.getIndex());
            lastIndexNum = randomIntBetween(lastIndexNum + 1, lastIndexNum + 50);
        }

        // create some backing indices for a data stream
        final List<Index> backingIndices = new ArrayList<>();
        int lastBackingIndexNum = 0;
        for (int k = 1; k <= numBackingIndices; k++) {
            lastBackingIndexNum = randomIntBetween(lastBackingIndexNum + 1, lastBackingIndexNum + 50);
            IndexMetadata im = IndexMetadata.builder(DataStream.getDefaultBackingIndexName(dataStreamName, lastBackingIndexNum))
                .settings(settings(Version.CURRENT))
                .numberOfShards(1)
                .numberOfReplicas(1)
                .build();
            b.put(im, false);
            backingIndices.add(im.getIndex());
        }
        b.put(new DataStream(dataStreamName, createTimestampField("@timestamp"), backingIndices, lastBackingIndexNum, null));
        return new CreateIndexResult(indices, backingIndices, b.build());
    }

    private static class CreateIndexResult {
        final List<Index> indices;
        final List<Index> backingIndices;
        final Metadata metadata;

        CreateIndexResult(List<Index> indices, List<Index> backingIndices, Metadata metadata) {
            this.indices = indices;
            this.backingIndices = backingIndices;
            this.metadata = metadata;
        }
    }
}
