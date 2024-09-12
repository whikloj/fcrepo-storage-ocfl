/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */

package org.fcrepo.storage.ocfl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.ocfl.api.MutableOcflRepository;
import io.ocfl.api.exception.FixityCheckException;
import io.ocfl.api.model.ObjectVersionId;
import io.ocfl.core.OcflRepositoryBuilder;
import io.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import io.ocfl.core.path.mapper.LogicalPathMappers;
import io.ocfl.core.storage.OcflStorageBuilder;
import org.apache.commons.lang3.SystemUtils;
import org.fcrepo.storage.ocfl.cache.CaffeineCache;
import org.fcrepo.storage.ocfl.cache.NoOpCache;
import org.fcrepo.storage.ocfl.exception.InvalidContentException;
import org.fcrepo.storage.ocfl.exception.NotFoundException;
import org.fcrepo.storage.ocfl.exception.ValidationException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * @author pwinckles
 */
@RunWith(Parameterized.class)
public class DefaultOcflObjectSessionTest {

    private final Logger LOGGER = getLogger(DefaultOcflObjectSessionTest.class);

    @Rule
    public TemporaryFolder temp = TemporaryFolder.builder().assureDeletion().build();

    @Parameterized.Parameters
    public static Collection<Boolean> data() {
        return List.of(false, true);
    }

    private Path ocflRoot;
    private Path sessionStaging;
    private final boolean useUnsafeWrite;

    private MutableOcflRepository ocflRepo;
    private OcflObjectSessionFactory sessionFactory;
    private OcflObjectSessionFactory cachedSessionFactory;
    private Cache<String, ResourceHeaders> headersCache;
    private Cache<String, String> rootIdCache;

    private static final String ROOT = "info:fedora";
    private static final String DEFAULT_AG_ID = "info:fedora/foo";
    private static final String DEFAULT_AG_BINARY_ID = "info:fedora/foo/bar";
    private static final String DEFAULT_MESSAGE = "F6 migration";
    private static final String DEFAULT_USER = "fedoraAdmin";
    private static final String DEFAULT_ADDRESS = "info:fedora/fedoraAdmin";

    public DefaultOcflObjectSessionTest(final boolean useUnsafeWrite) {
        this.useUnsafeWrite = useUnsafeWrite;
    }

    @Before
    public void setup() throws IOException {
        ocflRoot = temp.newFolder("ocfl").toPath();
        sessionStaging = temp.newFolder("staging").toPath();
        final var ocflTemp = temp.newFolder("ocfl-temp").toPath();

        final var logicalPathMapper = SystemUtils.IS_OS_WINDOWS ?
                LogicalPathMappers.percentEncodingWindowsMapper() : LogicalPathMappers.percentEncodingLinuxMapper();

        ocflRepo = new OcflRepositoryBuilder()
                .defaultLayoutConfig(new HashedNTupleLayoutConfig())
                .logicalPathMapper(logicalPathMapper)
                .storage(OcflStorageBuilder.builder().fileSystem(ocflRoot).build())
                .workDir(ocflTemp)
                .buildMutable();

        final var objectMapper = new ObjectMapper()
                .configure(WRITE_DATES_AS_TIMESTAMPS, false)
                .registerModule(new JavaTimeModule())
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);

        sessionFactory = new DefaultOcflObjectSessionFactory(ocflRepo,
                sessionStaging,
                objectMapper,
                new NoOpCache<>(),
                new NoOpCache<>(),
                CommitType.NEW_VERSION, DEFAULT_MESSAGE, DEFAULT_USER, DEFAULT_ADDRESS);
        sessionFactory.useUnsafeWrite(useUnsafeWrite);

        headersCache = Caffeine.newBuilder().maximumSize(100).build();
        rootIdCache = Caffeine.newBuilder().maximumSize(100).build();

        cachedSessionFactory = new DefaultOcflObjectSessionFactory(ocflRepo,
                sessionStaging,
                objectMapper,
                new CaffeineCache<>(headersCache),
                new CaffeineCache<>(rootIdCache),
                CommitType.NEW_VERSION, DEFAULT_MESSAGE, DEFAULT_USER, DEFAULT_ADDRESS);
        cachedSessionFactory.useUnsafeWrite(useUnsafeWrite);
    }

    @Test
    public void writeNewNonRdfResource() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);

        final var contentStr = "Test";
        final var content = ResourceUtils.atomicBinary(resourceId, "info:fedora/foo", contentStr);

        write(session, content);

        final var stagedContent = session.readContent(resourceId);

        assertResourceContent(contentStr, content, stagedContent);

        session.commit();

        final var committedContent = session.readContent(resourceId);

        assertResourceContent(contentStr, content, committedContent);
    }

    @Test
    public void writeUpdateNonRdfResource() {
        final var resourceId = "info:fedora/foo/bar";

        final var session1 = sessionFactory.newSession(resourceId);
        final var content1 = ResourceUtils.atomicBinary(resourceId, "info:fedora/foo", "Test");

        write(session1, content1);
        session1.commit();

        final var session2 = sessionFactory.newSession(resourceId);
        final var contentStr2 = "Updated!";
        final var content2 = ResourceUtils.atomicBinary(resourceId, "info:fedora/foo", contentStr2);

        write(session2, content2);
        session2.commit();

        final var committedContent = session2.readContent(resourceId);

        assertResourceContent(contentStr2, content2, committedContent);

        assertEquals(2, ocflRepo.describeObject(resourceId).getVersionMap().size());
    }

    @Test
    public void updateResourceHeaders() {
        final var resourceId = "info:fedora/foo/bar";

        final var session1 = sessionFactory.newSession(resourceId);
        final var content1 = ResourceUtils.atomicBinary(resourceId, "info:fedora/foo", "Test");

        write(session1, content1);
        session1.commit();

        final var session2 = sessionFactory.newSession(resourceId);

        final var timestamp = Instant.now().plusSeconds(60);
        final var state = "new-state-token";

        final var existingHeaders = session2.readHeaders(resourceId);
        final var updatedHeaders = ResourceHeaders.builder(existingHeaders)
                .withLastModifiedDate(timestamp)
                .withStateToken(state)
                .build();

        session2.writeHeaders(updatedHeaders);

        assertEquals(updatedHeaders, session2.readHeaders(resourceId));
        assertEquals(existingHeaders, sessionFactory.newSession(resourceId).readHeaders(resourceId));

        session2.commit();

        assertEquals(updatedHeaders, session2.readHeaders(resourceId));
    }

    @Test
    public void writeNewAtomicRdfResource() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);

        final var contentStr = "Test";
        final var content = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", contentStr);

        write(session, content);

        final var stagedContent = session.readContent(resourceId);

        assertResourceContent(contentStr, content, stagedContent);

        session.commit();

        final var committedContent = session.readContent(resourceId);

        assertResourceContent(contentStr, content, committedContent);
    }

    @Test
    public void writeNewAg() {
        final var agId = "info:fedora/foo";
        final var containerId = "info:fedora/foo/bar";
        final var binaryId = "info:fedora/foo/bar/baz";

        final var session = sessionFactory.newSession(agId);

        final var agContent = ResourceUtils.ag(agId, ROOT, "foo");
        final var containerContent = ResourceUtils.partContainer(containerId, agId, agId, "bar");
        final var binaryContent = ResourceUtils.partBinary(binaryId, containerId, agId, "baz");

        write(session, agContent);
        write(session, containerContent);
        write(session, binaryContent);

        final var stagedContent = session.readContent(binaryId);

        assertResourceContent("baz", binaryContent, stagedContent);

        session.commit();

        final var correctAg = touch(agContent, binaryContent);

        final var committedAg = session.readContent(agId);
        final var committedContainer = session.readContent(containerId);
        final var committedBinary = session.readContent(binaryId);

        assertResourceContent("foo", correctAg, committedAg);
        assertResourceContent("bar", containerContent, committedContainer);
        assertResourceContent("baz", binaryContent, committedBinary);
    }

    @Test
    public void rollbackAllAgChangesWhenAutoVersionedWithMultipleVersions() {
        final var agId = "info:fedora/foo";
        final var containerId = "info:fedora/foo/bar";
        final var binaryId = "info:fedora/foo/bar/baz";

        final var session = sessionFactory.newSession(agId);

        final var agContent = ResourceUtils.ag(agId, ROOT, "foo");
        final var containerContent = ResourceUtils.partContainer(containerId, agId, agId, "bar");
        final var binaryContent = ResourceUtils.partBinary(binaryId, containerId, agId, "baz");

        write(session, agContent);
        write(session, containerContent);
        write(session, binaryContent);

        session.commit();

        final var session2 = sessionFactory.newSession(agId);

        final var agContent2 = ResourceUtils.ag(agId, ROOT, "foo2");
        final var containerContent2 = ResourceUtils.partContainer(containerId, agId, agId, "bar2");
        final var deleteHeaders = ResourceHeaders.builder(binaryContent.getHeaders())
                .withDeleted(true)
                .withContentPath(null)
                .withContentSize(-1)
                .withDigests(null)
                .build();

        write(session2, agContent2);
        write(session2, containerContent2);
        session2.deleteContentFile(deleteHeaders);

        session2.commit();

        final var correctAg = touch(agContent2, deleteHeaders);

        assertResourceContent("foo2", correctAg, session2.readContent(agId));
        assertResourceContent("bar2", containerContent2, session2.readContent(containerId));
        final var deletedBinary = session2.readContent(binaryId);
        assertEquals(deleteHeaders, deletedBinary.getHeaders());
        assertFalse(deletedBinary.getContentStream().isPresent());

        session2.rollback();

        final var correctAg2 = touch(agContent, binaryContent);

        assertResourceContent("foo", correctAg2, session2.readContent(agId));
        assertResourceContent("bar", containerContent, session2.readContent(containerId));
        assertResourceContent("baz", binaryContent, session2.readContent(binaryId));
    }

    @Test
    public void rollbackWhenAutoVersioningEnabledAndFirstVersion() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);

        final var content = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", "test");

        write(session, content);

        session.commit();

        assertResourceContent("test", content, session.readContent(resourceId));

        session.rollback();

        expectResourceNotFound(resourceId, session);
    }

    @Test
    public void removeStagedContentWhenRollbackOnSessionThatIsNotCommitted() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);

        final var content = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", "test");

        write(session, content);

        assertResourceContent("test", content, session.readContent(resourceId));

        session.rollback();

        expectResourceNotFound(resourceId, session);
    }

    @Test
    public void failCommitWhenSessionRolledback() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);

        final var content = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", "test");

        write(session, content);

        assertResourceContent("test", content, session.readContent(resourceId));

        session.rollback();

        expectResourceNotFound(resourceId, session);

        try {
            session.commit();
            fail("Should have thrown an exception");
        } catch (IllegalStateException e) {
            // expected exception
        }
    }

    @Test
    public void allowRollbackWhenManualVersioningUsedButThereWasNotAnExistingMutableHead() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);
        session.commitType(CommitType.UNVERSIONED);

        final var content = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", "test");

        write(session, content);
        session.commit();

        assertResourceContent("test", content, session.readContent(resourceId));

        session.rollback();

        expectResourceNotFound(resourceId, session);
    }

    @Test(expected = IllegalStateException.class)
    public void throwExceptionOnRollbackWhenAutoVersioningUsedOnExistingMutableVersion() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);
        session.commitType(CommitType.UNVERSIONED);

        final var content = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", "test");

        write(session, content);
        session.commit();

        final var session2 = sessionFactory.newSession(resourceId);

        final var content2 = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", "test2");

        write(session2, content2);
        session2.commit();

        assertResourceContent("test2", content2, session2.readContent(resourceId));

        session2.rollback();
    }

    @Test
    public void cacheTest() {
        final var agId = "info:fedora/foo";
        final var containerId = "info:fedora/foo/bar";
        final var binaryId = "info:fedora/foo/bar/baz";

        final var session = cachedSessionFactory.newSession(agId);

        final var agContent = ResourceUtils.ag(agId, ROOT, "foo");
        final var containerContent = ResourceUtils.partContainer(containerId, agId, agId, "bar");
        final var binaryContent = ResourceUtils.partBinary(binaryId, containerId, agId, "baz");

        write(session, agContent);
        write(session, containerContent);
        write(session, binaryContent);
        final var correctAg = touch(agContent, binaryContent);

        final var stagedContent = session.readContent(binaryId);

        assertResourceContent("baz", binaryContent, stagedContent);

        assertEquals(0, headersCache.estimatedSize());

        session.commit();

        assertEquals(3, headersCache.estimatedSize());
        assertTrue(headersCache.asMap().containsKey(agId + "_v1"));
        assertTrue(headersCache.asMap().containsKey(containerId + "_v1"));
        assertTrue(headersCache.asMap().containsKey(binaryId + "_v1"));

        final var committedAg = session.readContent(agId);
        final var committedContainer = session.readContent(containerId);
        final var committedBinary = session.readContent(binaryId);

        assertResourceContent("foo", correctAg, committedAg);
        assertResourceContent("bar", containerContent, committedContainer);
        assertResourceContent("baz", binaryContent, committedBinary);
    }

    @Test
    public void writeNewBinaryAcl() {
        final var resourceId = "info:fedora/foo/bar";
        final var aclId = "info:fedora/foo/bar/fcr:acl";

        final var session = sessionFactory.newSession(resourceId);

        final var content = ResourceUtils.atomicBinary(resourceId, "info:fedora/foo", "blah");
        final var acl = ResourceUtils.atomicBinaryAcl(aclId, resourceId, "acl");

        write(session, content);
        write(session, acl);

        session.commit();

        final var committedAcl = session.readContent(aclId);

        assertResourceContent("acl", acl, committedAcl);
    }

    @Test
    public void writeNewContainerAcl() {
        final var resourceId = "info:fedora/foo/bar";
        final var aclId = "info:fedora/foo/bar/fcr:acl";

        final var session = sessionFactory.newSession(resourceId);

        final var content = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", "blah");
        final var acl = ResourceUtils.atomicContainerAcl(aclId, resourceId, "acl");

        write(session, content);
        write(session, acl);

        session.commit();

        final var committedAcl = session.readContent(aclId);

        assertResourceContent("acl", acl, committedAcl);
    }

    @Test
    public void writeNewBinaryDesc() {
        final var resourceId = "info:fedora/foo/bar";
        final var descId = "info:fedora/foo/bar/fcr:metadata";

        final var session = sessionFactory.newSession(resourceId);

        final var content = ResourceUtils.atomicBinary(resourceId, "info:fedora/foo", "blah");
        final var desc = ResourceUtils.atomicDesc(descId, resourceId, "desc");

        write(session, content);
        write(session, desc);

        session.commit();

        final var committedDesc = session.readContent(descId);

        assertResourceContent("desc", desc, committedDesc);
    }

    @Test(expected = NotFoundException.class)
    public void throwExceptionWhenObjectDoesNotExist() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);
        session.readContent(resourceId);
    }

    @Test(expected = NotFoundException.class)
    public void throwExceptionWhenObjectExistButResourceDoesNot() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);

        write(session, ResourceUtils.atomicBinary(resourceId, "info:fedora/foo", "blah"));
        session.commit();

        session.readContent(resourceId + "/baz");
    }

    @Test
    public void deleteFileThatHasBeenCommitted() {
        close(defaultAg());
        final var agBinary = defaultAgBinary();
        close(agBinary);

        final var session1 = sessionFactory.newSession(DEFAULT_AG_ID);

        final var existing = session1.readContent(DEFAULT_AG_BINARY_ID);

        assertEquals(agBinary.getHeaders(), existing.getHeaders());
        assertTrue(existing.getContentStream().isPresent());
        close(existing);

        final var deleteHeaders = ResourceHeaders.builder(existing.getHeaders())
                .withContentPath(null)
                .withContentSize(-1)
                .withDigests(null)
                .withDeleted(true)
                .build();

        session1.deleteContentFile(deleteHeaders);

        final var stagedDelete = session1.readContent(DEFAULT_AG_BINARY_ID);

        assertEquals(deleteHeaders, stagedDelete.getHeaders());
        assertFalse(stagedDelete.getContentStream().isPresent());

        session1.commit();

        final var deleted = session1.readContent(DEFAULT_AG_BINARY_ID);

        assertEquals(deleteHeaders, deleted.getHeaders());
        assertFalse(deleted.getContentStream().isPresent());

        final var session2 = sessionFactory.newSession(DEFAULT_AG_ID);

        session2.deleteResource(DEFAULT_AG_BINARY_ID);

        expectResourceNotFound(DEFAULT_AG_BINARY_ID, session2);

        session2.commit();

        expectResourceNotFound(DEFAULT_AG_BINARY_ID, session2);
    }

    @Test
    public void deleteFileThatHasNotBeenCommitted() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        final var ag = ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag");
        final var binary = ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "binary");

        write(session, ag);
        write(session, binary);
        final var correctAg = touch(ag, binary);

        session.deleteContentFile(binary.getHeaders());

        expectResourceNotFound(DEFAULT_AG_BINARY_ID, session);

        session.commit();

        expectResourceNotFound(DEFAULT_AG_BINARY_ID, session);
        assertResourceContent("ag", correctAg, session.readContent(DEFAULT_AG_ID));
    }

    @Test
    public void deleteFileAndThenReAddBeforeCommitting() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        final var ag = ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag");
        final var binary = ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "binary");
        final var binary2 = ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "binary2");
        final var correctAg = touch(ag, binary2);

        write(session, ag);
        write(session, binary);

        session.deleteContentFile(binary.getHeaders());
        expectResourceNotFound(DEFAULT_AG_BINARY_ID, session);

        write(session, binary2);

        session.commit();

        assertResourceContent("ag", correctAg, session.readContent(DEFAULT_AG_ID));
        assertResourceContent("binary2", binary2, session.readContent(DEFAULT_AG_BINARY_ID));
    }

    @Test
    public void deleteObjectWhenAlreadyCommitted() {
        close(defaultAg());
        close(defaultAgBinary());

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        session.deleteResource(DEFAULT_AG_ID);
        session.commit();

        expectResourceNotFound(DEFAULT_AG_ID, session);
        expectResourceNotFound(DEFAULT_AG_BINARY_ID, session);

        assertFalse(ocflRepo.containsObject(DEFAULT_AG_ID));
    }

    @Test
    public void deleteObjectWhenNotCommitted() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        final var ag = ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag");
        final var binary = ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "binary");

        write(session, ag);
        write(session, binary);

        session.deleteResource(DEFAULT_AG_ID);

        session.commit();

        expectResourceNotFound(DEFAULT_AG_ID, session);
        expectResourceNotFound(DEFAULT_AG_BINARY_ID, session);

        assertFalse(ocflRepo.containsObject(DEFAULT_AG_ID));
    }

    @Test
    public void addedFilesToDeletedObject() {
        close(defaultAg());
        close(defaultAgBinary());

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        session.deleteResource(DEFAULT_AG_ID);

        final var ag2 = ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag2");

        write(session, ag2);

        session.commit();

        expectResourceNotFound(DEFAULT_AG_BINARY_ID, session);
        assertResourceContent("ag2", ag2, session.readContent(DEFAULT_AG_ID));
    }

    @Test
    public void readHeadersForResourceMarkedAsDeleted() {
        final var ag = defaultAg();
        close(ag);

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        final var deleteHeaders = ResourceHeaders.builder(ag.getHeaders())
                .withDeleted(true)
                .withContentPath(null)
                .withContentSize(-1)
                .withDigests(null)
                .build();

        session.deleteContentFile(deleteHeaders);
        session.commit();

        final var deletedContent = session.readContent(DEFAULT_AG_ID);

        assertEquals(deleteHeaders, deletedContent.getHeaders());
        assertFalse(deletedContent.getContentStream().isPresent());
    }

    @Test
    public void setOcflVersionInfo() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        final var ag = ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag");

        write(session, ag);

        final var created = OffsetDateTime.now().minusWeeks(2);
        final var message = "Special message!";
        final var author = "John Doe";
        final var address = "jdoe@example.com";

        session.versionAuthor(author, address);
        session.versionMessage(message);
        session.versionCreationTimestamp(created);

        session.commit();

        final var desc = ocflRepo.describeVersion(ObjectVersionId.head(DEFAULT_AG_ID));

        assertEquals(created, desc.getCreated());
        assertEquals(message, desc.getVersionInfo().getMessage());
        assertEquals(author, desc.getVersionInfo().getUser().getName());
        assertEquals(address, desc.getVersionInfo().getUser().getAddress());
    }

    @Test
    public void abortStagedChanges() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        final var ag = ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag");
        write(session, ag);

        session.abort();

        assertFalse(ocflRepo.containsObject(DEFAULT_AG_ID));
    }

    @Test
    public void failWriteAfterSessionClosed() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        final var ag = ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag");
        write(session, ag);

        session.commit();

        try {
            write(session, ag);
            fail("Should have thrown an exception");
        } catch (IllegalStateException e) {
            // expected exception
        }
    }

    @Test
    public void failCommitAfterSessionClosed() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        final var ag = ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag");
        write(session, ag);

        session.commit();

        try {
            session.commit();
            fail("Should have thrown an exception");
        } catch (IllegalStateException e) {
            // expected exception
        }
    }

    @Test
    public void ensurePathsAreSafeForWindows() {
        final var resourceId = "info:fedora/foo:bar";
        final var session = sessionFactory.newSession(resourceId);

        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "stuff");

        write(session, content);

        final var stagedContent = session.readContent(resourceId);
        assertResourceContent("stuff", content, stagedContent);

        session.commit();

        final var committedContent = session.readContent(resourceId);
        assertResourceContent("stuff", content, committedContent);
    }

    @Test
    public void doNothingWhenNoStagedChangesAndObjectDoesNotExist() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        session.commit();
        assertFalse(ocflRepo.containsObject(DEFAULT_AG_ID));
    }

    @Test
    public void doNothingWhenNoStagedChangesAndObjectExist() {
        close(defaultAg());
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        session.commit();
        assertEquals(1, ocflRepo.describeObject(DEFAULT_AG_ID).getVersionMap().size());
    }

    @Test
    public void restoreAtomicRdfResource() {
        final var resourceId = "info:fedora/foo/bar";
        final var session = sessionFactory.newSession(resourceId);

        final var contentStr = "Test";
        final var content = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", contentStr);

        write(session, content);

        final var stagedContent = session.readContent(resourceId);

        assertResourceContent(contentStr, content, stagedContent);

        session.commit();

        final var committedContent = session.readContent(resourceId);

        assertResourceContent(contentStr, content, committedContent);

        // Start a second session to delete and then recreate the resource
        final var session2 = sessionFactory.newSession(resourceId);
        session2.deleteResource(resourceId);

        final var contentStr2 = "Test more";
        final var content2 = ResourceUtils.atomicContainer(resourceId, "info:fedora/foo", contentStr2);

        write(session2, content2);

        final var stagedContent2 = session2.readContent(resourceId);
        assertResourceContent(contentStr2, content2, stagedContent2);

        session2.commit();

        final var committedContent2 = session2.readContent(resourceId);

        assertResourceContent(contentStr2, content2, committedContent2);
    }

    @Test(expected = ValidationException.class)
    public void failWriteFileWithNullContentNotExternal() {
        final var resourceId = "info:fedora/foo";
        final var session = sessionFactory.newSession(resourceId);

        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, null);

        write(session, content);
    }

    @Test
    public void listAgVersions() {
        final var binary2Id = DEFAULT_AG_ID + "/baz";

        final var session1 = sessionFactory.newSession(DEFAULT_AG_ID);

        write(session1, ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag"));
        write(session1, ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "binary"));
        session1.commit();

        final var session2 = sessionFactory.newSession(DEFAULT_AG_ID);

        write(session2, ResourceUtils.partBinary(binary2Id, DEFAULT_AG_ID, DEFAULT_AG_ID, "binary2"));
        session2.commit();

        final var session3 = sessionFactory.newSession(DEFAULT_AG_ID);

        write(session3, ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "updated"));
        session3.commit();

        final var session4 = sessionFactory.newSession(DEFAULT_AG_ID);

        assertVersions(session4.listVersions(DEFAULT_AG_ID), "v1", "v2", "v3");
        assertVersions(session4.listVersions(DEFAULT_AG_BINARY_ID), "v1", "v3");
        assertVersions(session4.listVersions(binary2Id), "v2");
    }

    @Test(expected = NotFoundException.class)
    public void throwExceptionWhenListingVersionsOnObjectThatDoesNotExist() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        session.listVersions(DEFAULT_AG_ID);
    }

    @Test(expected = NotFoundException.class)
    public void throwExceptionWhenListingVersionsOnResourceThatDoesNotExist() {
        close(defaultAg());
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        session.listVersions(DEFAULT_AG_BINARY_ID);
    }

    @Test
    public void returnEmptyVersionsWhenResourceIsStagedButNotInOcfl() {
        close(defaultAg());
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        write(session, ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "test"));

        assertEquals(0, session.listVersions(DEFAULT_AG_BINARY_ID).size());
    }

    @Test
    public void returnEmptyVersionsWhenResourceOnlyExistInMutableHead() {
        close(defaultAg());
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        session.commitType(CommitType.UNVERSIONED);

        write(session, ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "test"));
        session.commit();

        assertEquals(0, session.listVersions(DEFAULT_AG_BINARY_ID).size());
        assertEquals(1, session.listVersions(DEFAULT_AG_ID).size());
    }

    @Test
    public void readPreviousVersion() {
        final var resourceId = "info:fedora/foo";

        final var first = ResourceUtils.atomicBinary(resourceId, ROOT, "first");
        final var second = ResourceUtils.atomicBinary(resourceId, ROOT, "second");
        final var third = ResourceUtils.atomicBinary(resourceId, ROOT, "third");

        final var session1 = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session1, first);
        session1.commit();

        final var session2 = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session2, second);
        session2.commit();

        final var session3 = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session3, third);
        session3.commit();

        assertResourceContent("first", first, session3.readContent(resourceId, "v1"));
        assertResourceContent("second", second, session3.readContent(resourceId, "v2"));
        assertResourceContent("third", third, session3.readContent(resourceId, "v3"));

        try {
            session3.readContent(resourceId, "v4");
            fail("Expected an exception because the version should not exist");
        } catch (NotFoundException e) {
            // expected exception
        }
    }

    @Test
    public void failWhenProvidedDigestDoesNotMatchComputed() {
        final var resourceId = "info:fedora/foo";
        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "bar", headers -> {
            headers.withDigests(List.of(
                    URI.create("urn:sha-512:dc6b68d13b8cf959644b935f1192b02c71aa7a5cf653bd43b4480fa89eec8d4d3f16a" +
                            "2278ec8c3b40ab1fdb233b3173a78fd83590d6f739e0c9e8ff56c282557")));
        });

        final var session = sessionFactory.newSession(resourceId);

        write(session, content);

        try {
            session.commit();
            if (useUnsafeWrite) {
                // an exception should not be thrown when unsafe writes are used
            } else {
                fail("Commit should have thrown a FixityCheckException");
            }
        } catch (FixityCheckException e) {
            if (!useUnsafeWrite) {
                // this is expected
            } else {
                fail("Commit should not have thrown a FixityCheckException");
            }
        }
    }

    @Test
    public void addOcflDigestWhenNotProvided() throws URISyntaxException {
        final var resourceId = "info:fedora/foo";

        final var sha512Digest = "d82c4eb5261cb9c8aa9855edd67d1bd10482f41529858d925094d173fa662aa" +
                "91ff39bc5b188615273484021dfb16fd8284cf684ccf0fc795be3aa2fc1e6c181";
        final var sha256Digest = "fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9";

        final var digests = new ArrayList<URI>();
        digests.add(new URI("urn:sha-256:" + sha256Digest));

        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "bar", headers -> {
            headers.withDigests(digests);
        });

        final var session = sessionFactory.newSession(resourceId);

        write(session, content);
        session.commit();

        final var headers = session.readHeaders(resourceId);

        assertThat(headers.getDigests(), containsInAnyOrder(
                new URI("urn:sha-256:" + sha256Digest),
                new URI("urn:sha-512:" + sha512Digest)));
    }

    @Test
    public void commitToMutableHeadWhenNewObject() {
        final var resourceId = "info:fedora/foo";

        final var content1 = ResourceUtils.atomicBinary(resourceId, ROOT, "first");
        final var session1 = sessionFactory.newSession(resourceId);
        session1.commitType(CommitType.UNVERSIONED);

        write(session1, content1);
        session1.commit();

        assertResourceContent("first", content1, session1.readContent(resourceId));

        final var content2 = ResourceUtils.atomicBinary(resourceId, ROOT, "second");
        final var session2 = sessionFactory.newSession(resourceId);
        session2.commitType(CommitType.UNVERSIONED);

        write(session2, content2);
        session2.commit();

        assertResourceContent("second", content2, session2.readContent(resourceId));

        assertEquals(0, session2.listVersions(resourceId).size());
    }

    @Test
    public void commitToMutableHeadWhenHasExistingVersion() {
        final var resourceId = "info:fedora/foo";

        final var content1 = ResourceUtils.atomicBinary(resourceId, ROOT, "first");
        final var session1 = sessionFactory.newSession(resourceId);

        write(session1, content1);
        session1.commit();

        assertResourceContent("first", content1, session1.readContent(resourceId));

        final var content2 = ResourceUtils.atomicBinary(resourceId, ROOT, "second");
        final var session2 = sessionFactory.newSession(resourceId);
        session2.commitType(CommitType.UNVERSIONED);

        write(session2, content2);
        session2.commit();

        assertResourceContent("second", content2, session2.readContent(resourceId));

        assertEquals(1, session2.listVersions(resourceId).size());

        assertResourceContent("first", content1, session2.readContent(resourceId, "v1"));
    }

    @Test
    public void commitNewVersionWhenHasStagedChanges() {
        final var resourceId = "info:fedora/foo";

        final var content1 = ResourceUtils.atomicBinary(resourceId, ROOT, "first");
        final var session1 = sessionFactory.newSession(resourceId);
        session1.commitType(CommitType.UNVERSIONED);

        write(session1, content1);
        session1.commit();

        assertResourceContent("first", content1, session1.readContent(resourceId));

        final var content2 = ResourceUtils.atomicBinary(resourceId, ROOT, "second");
        final var session2 = sessionFactory.newSession(resourceId);

        write(session2, content2);
        session2.commit();

        assertResourceContent("second", content2, session2.readContent(resourceId));

        assertEquals(1, session2.listVersions(resourceId).size());

        assertResourceContent("second", content2, session2.readContent(resourceId, "v2"));
    }

    @Test
    public void listResourcesWhenOnlyOneWithNothingStaged() {
        final var ag = defaultAg();
        close(ag);

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        final var resources = session.streamResourceHeaders().collect(Collectors.toList());

        assertThat(resources, containsInAnyOrder(ag.getHeaders()));
    }

    @Test
    public void listResourcesWhenMultipleWithNothingStaged() {
        final var ag = defaultAg();
        final var binary = defaultAgBinary();
        close(ag);
        close(binary);
        // Correct ag last modified and state token to binary child creation.
        final var correctAg = touch(ag, binary);

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        final var resources = listHeaders(session);

        assertHeaders(resources, correctAg.getHeaders(), binary.getHeaders());
    }

    @Test
    public void listResourcesWhenMultipleWithStagedChanges() {
        final var ag = defaultAg();
        final var binary = defaultAgBinary();
        close(ag);
        close(binary);

        final var binaryUpdate = ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID,
                DEFAULT_AG_ID, DEFAULT_AG_ID, "updated!");

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session, binaryUpdate);

        // Match ag last modified and state token to binary child update.
        final var correctAg = touch(ag, binaryUpdate);

        final var resources = listHeaders(session);

        assertHeaders(resources, correctAg.getHeaders(), binaryUpdate.getHeaders());
    }

    @Test
    public void listResourcesWhenMultipleWithStagedDeletes() {
        final var ag = defaultAg();
        final var binary = defaultAgBinary();
        close(ag);
        close(binary);

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);

        final var deleteHeaders = ResourceHeaders.builder(binary.getHeaders())
                .withDeleted(true)
                .withContentPath(null)
                .withContentSize(-1)
                .withDigests(null)
                .build();

        session.deleteContentFile(deleteHeaders);
        // Match ag lastmodified and state token to binary child.
        final var correctAg1 = touch(ag, binary);

        final var resources = listHeaders(session);
        assertHeaders(resources, correctAg1.getHeaders(), deleteHeaders);

        session.deleteResource(DEFAULT_AG_BINARY_ID);

        // Match ag lastmodified and state token to deletion of binary child.
        final var correctAg2 = touch(ag, deleteHeaders);
        final var resources2 = listHeaders(session);
        assertHeaders(resources2, correctAg2.getHeaders());
    }

    @Test
    public void listResourceWhenNotCreated() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        final var resources = session.streamResourceHeaders().collect(Collectors.toList());
        assertEquals(0, resources.size());
    }

    @Test
    public void listResourceWhenPendingDelete() {
        close(defaultAg());
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        session.deleteResource(DEFAULT_AG_ID);
        final var resources = session.streamResourceHeaders().collect(Collectors.toList());
        assertEquals(0, resources.size());
    }

    @Test
    public void containsResourceWhenExistsInOcfl() {
        close(defaultAg());
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        assertTrue(session.containsResource(DEFAULT_AG_ID));
    }

    @Test
    public void containsResourceWhenExistsInStaging() {
        final var resourceId = "info:fedora/foo";

        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "first");
        final var session = sessionFactory.newSession(resourceId);

        write(session, content);

        assertTrue(session.containsResource(resourceId));
    }

    @Test
    public void containsResourceWhenContentDeleted() {
        final var resourceId = "info:fedora/foo";

        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "first");
        final var session = sessionFactory.newSession(resourceId);

        write(session, content);
        session.commit();

        final var session2 = sessionFactory.newSession(resourceId);

        session2.deleteContentFile(ResourceHeaders.builder(content.getHeaders())
                .withDeleted(true)
                .build());

        assertTrue(session2.containsResource(resourceId));
    }

    @Test
    public void notContainsResourceWhenPendingDelete() {
        final var resourceId = "info:fedora/foo";

        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "first");
        final var session = sessionFactory.newSession(resourceId);

        write(session, content);
        session.commit();

        final var session2 = sessionFactory.newSession(resourceId);

        session2.deleteResource(resourceId);

        assertFalse(session2.containsResource(resourceId));
    }

    @Test
    public void notContainsResourceWhenObjectNotExists() {
        final var resourceId = "info:fedora/foo";
        final var session = sessionFactory.newSession(resourceId);
        assertFalse(session.containsResource(resourceId));
    }

    @Test
    public void notContainsResourceWhenNotExists() {
        close(defaultAg());
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        assertFalse(session.containsResource(DEFAULT_AG_BINARY_ID));
    }

    @Test
    public void readPreviousVersionWhenHasChangesPending() {
        final var resourceId = "info:fedora/foo";

        final var first = ResourceUtils.atomicBinary(resourceId, ROOT, "first");
        final var second = ResourceUtils.atomicBinary(resourceId, ROOT, "second");
        final var third = ResourceUtils.atomicBinary(resourceId, ROOT, "third");

        final var session1 = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session1, first);
        session1.commit();

        final var session2 = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session2, second);
        session2.commit();

        final var session3 = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session3, third);

        assertResourceContent("first", first, session3.readContent(resourceId, "v1"));
        assertResourceContent("second", second, session3.readContent(resourceId, "v2"));
        assertResourceContent("third", third, session3.readContent(resourceId));
    }

    @Test
    public void touchAgWhenAgPartUpdated() {
        close(defaultAg());

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        assertVersions(session.listVersions(DEFAULT_AG_ID), "v1");

        close(defaultAgBinary());

        final var session2 = sessionFactory.newSession(DEFAULT_AG_ID);
        assertVersions(session2.listVersions(DEFAULT_AG_ID), "v1", "v2");
        assertVersions(session2.listVersions(DEFAULT_AG_BINARY_ID), "v2");
    }

    @Test
    public void touchingShouldUseTheTimestampFromTheLastUpdatedResource() throws InterruptedException {
        close(defaultAg());

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        assertVersions(session.listVersions(DEFAULT_AG_ID), "v1");

        final var binary = ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "bar");
        final var timestamp = binary.getHeaders().getLastModifiedDate();
        LOGGER.info("timestamp is " + timestamp.toString());

        TimeUnit.SECONDS.sleep(1);

        final var session2 = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session2, binary);
        session2.commit();

        final var session3 = sessionFactory.newSession(DEFAULT_AG_ID);
        final var agHeaders = session3.readHeaders(DEFAULT_AG_ID);
        assertEquals(timestamp, agHeaders.getMementoCreatedDate());
        LOGGER.info("Now timestamp is " + timestamp + " and lastmodified is " + agHeaders.getLastModifiedDate());
        assertNotEquals(timestamp, agHeaders.getLastModifiedDate());
    }

    @Test
    public void doNotTouchAgWhenAclUpdated() {
        close(defaultAg());

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        assertVersions(session.listVersions(DEFAULT_AG_ID), "v1");

        final var resourceId = DEFAULT_AG_ID + "/fcr:acl";
        final var content = ResourceUtils.partContainerAcl(resourceId, DEFAULT_AG_ID, DEFAULT_AG_ID,"blah");

        write(session, content);
        session.commit();

        assertVersions(session.listVersions(DEFAULT_AG_ID), "v1");
        assertVersions(session.listVersions(resourceId), "v2");
    }

    @Test
    public void touchBinaryWhenDescUpdated() {
        final var resourceId = "info:fedora/foo";
        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "foo");

        final var descId = "info:fedora/foo/fcr:metadata";
        final var descContent = ResourceUtils.atomicDesc(descId, resourceId, "desc");

        final var session = sessionFactory.newSession(resourceId);

        write(session, content);
        write(session, descContent);
        session.commit();

        assertVersions(session.listVersions(resourceId), "v1");
        assertVersions(session.listVersions(descId), "v1");

        final var session2 = sessionFactory.newSession(resourceId);
        final var descContent2 = ResourceUtils.atomicDesc(descId, resourceId, "desc2");

        write(session2, descContent2);
        session2.commit();

        final var session3 = sessionFactory.newSession(resourceId);

        assertVersions(session3.listVersions(resourceId), "v1", "v2");
        assertVersions(session3.listVersions(descId), "v1", "v2");
    }

    @Test
    public void touchBinaryDescWhenBinaryUpdated() {
        final var resourceId = "info:fedora/foo";
        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "foo");

        final var descId = "info:fedora/foo/fcr:metadata";
        final var descContent = ResourceUtils.atomicDesc(descId, resourceId, "desc");

        final var session = sessionFactory.newSession(resourceId);

        write(session, content);
        write(session, descContent);
        session.commit();

        assertVersions(session.listVersions(resourceId), "v1");
        assertVersions(session.listVersions(descId), "v1");

        final var session2 = sessionFactory.newSession(resourceId);
        final var content2 = ResourceUtils.atomicBinary(resourceId, ROOT, "bar");

        write(session2, content2);
        session2.commit();

        assertVersions(session.listVersions(resourceId), "v1", "v2");
        assertVersions(session.listVersions(descId), "v1", "v2");
    }

    @Test
    public void touchBinaryWhenBinaryDescMementoCreated() {
        final var resourceId = "info:fedora/foo";
        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "foo");

        final var descId = "info:fedora/foo/fcr:metadata";
        final var descContent = ResourceUtils.atomicDesc(descId, resourceId, "desc");

        final var session = sessionFactory.newSession(resourceId);

        write(session, content);
        write(session, descContent);
        session.commit();

        assertVersions(session.listVersions(resourceId), "v1");
        assertVersions(session.listVersions(descId), "v1");

        final var session2 = sessionFactory.newSession(resourceId);

        session2.writeHeaders(ResourceHeaders.builder(session.readHeaders(descId))
                .withMementoCreatedDate(Instant.now())
                .build());
        session2.commit();

        assertVersions(session.listVersions(resourceId), "v1", "v2");
        assertVersions(session.listVersions(descId), "v1", "v2");
    }

    @Test
    public void failWriteWhenContentSizeDoesNotMatch() {
        final var resourceId = "info:fedora/foo";
        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "first", headers -> {
            headers.withContentSize(1024L);
        });

        final var session = sessionFactory.newSession(resourceId);

        try {
            write(session, content);
            fail("should have thrown an exception");
        } catch (InvalidContentException e) {
            assertThat(e.getMessage(), containsString("file size does not match"));
        }
    }

    @Test
    public void contentWriteFailuresShouldCleanupStagedFiles() throws IOException {
        final var resourceId = "info:fedora/foo";
        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "first", headers -> {
            headers.withContentSize(1024L);
        });

        final var session = sessionFactory.newSession(resourceId);

        try {
            write(session, content);
            fail("should have thrown an exception");
        } catch (RuntimeException e) {
            assertThat(recursiveList(sessionStaging), empty());
        }
    }

    @Test
    public void versioningMutableHeadAgShouldVersionPartsWithStagedChanges() {
        final var agId = "info:fedora/foo";
        final var containerId = "info:fedora/foo/bar";
        final var binaryId = "info:fedora/foo/bar/baz";
        final var binary2Id = "info:fedora/foo/bar/boz";

        // Create initial resources -- mutable head

        final var session = sessionFactory.newSession(agId);
        session.commitType(CommitType.UNVERSIONED);

        final var agContent = ResourceUtils.ag(agId, ROOT, "foo");
        final var containerContent = ResourceUtils.partContainer(containerId, agId, agId, "bar");
        final var binaryContent = ResourceUtils.partBinary(binaryId, containerId, agId, "baz");
        final var binary2Content = ResourceUtils.partBinary(binary2Id, containerId, agId, "boz");

        write(session, agContent);
        write(session, containerContent);
        write(session, binaryContent);
        write(session, binary2Content);

        session.commit();

        assertEquals(0, session.listVersions(agId).size());
        assertEquals(0, session.listVersions(containerId).size());
        assertEquals(0, session.listVersions(binaryId).size());
        assertEquals(0, session.listVersions(binary2Id).size());

        // Commit mutable head

        final var session2 = sessionFactory.newSession(agId);
        session2.commit();

        assertVersions(session2.listVersions(agId), "v2");
        assertVersions(session2.listVersions(containerId), "v2");
        assertVersions(session2.listVersions(binaryId), "v2");
        assertVersions(session2.listVersions(binary2Id), "v2");

        // Update a subset of resources -- mutable head

        final var session3 = sessionFactory.newSession(agId);
        session3.commitType(CommitType.UNVERSIONED);

        final var containerContentV2 = ResourceUtils.partContainer(containerId, agId, agId, "bar - 2");
        final var binaryContentV2 = ResourceUtils.partBinary(binaryId, containerId, agId, "baz - 2");

        write(session3, containerContentV2);
        write(session3, binaryContentV2);

        session3.commit();

        assertVersions(session3.listVersions(agId), "v2");
        assertVersions(session3.listVersions(containerId), "v2");
        assertVersions(session3.listVersions(binaryId), "v2");
        assertVersions(session3.listVersions(binary2Id), "v2");

        // Update a subset of resources again -- mutable head

        final var session4 = sessionFactory.newSession(agId);
        session4.commitType(CommitType.UNVERSIONED);

        final var containerContentV3 = ResourceUtils.partContainer(containerId, agId, agId, "bar - 3");

        write(session4, containerContentV3);

        session4.commit();

        assertVersions(session4.listVersions(agId), "v2");
        assertVersions(session4.listVersions(containerId), "v2");
        assertVersions(session4.listVersions(binaryId), "v2");
        assertVersions(session4.listVersions(binary2Id), "v2");

        // Commit mutable head

        final var session5 = sessionFactory.newSession(agId);
        session5.commit();

        assertVersions(session5.listVersions(agId), "v2", "v3");
        assertVersions(session5.listVersions(containerId), "v2", "v3");
        assertVersions(session5.listVersions(binaryId), "v2", "v3");
        assertVersions(session5.listVersions(binary2Id), "v2");
    }

    @Test
    public void testDeleteChildInAgUpdatesAg() throws Exception {
        // Create AG and child resource
        final var ag = defaultAg();
        final var binaryChild = defaultAgBinary();
        close(ag);
        close(binaryChild);
        // Time passes
        Thread.sleep(1000);

        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        final var now = Instant.now();
        // Mark the child as deleted
        final var deleteHeaders = ResourceHeaders.builder(binaryChild.getHeaders())
                .withContentPath(null)
                .withContentSize(-1)
                .withDigests(null)
                .withDeleted(true)
                .withLastModifiedDate(now)
                .withMementoCreatedDate(now)
                .withStateToken(ResourceUtils.getStateToken(now))
                .build();
        session.deleteContentFile(deleteHeaders);

        // The AG should have an updated timestamp to indicate it was altered
        final var correctAg = touch(ag, deleteHeaders);
        final var resources = listHeaders(session);
        assertHeaders(resources, correctAg.getHeaders(), deleteHeaders);
    }

    @Test(expected = ValidationException.class)
    public void failWhenInvalidResourceHeaders() {
        final var resourceId = "info:fedora/foo";
        final var content = ResourceUtils.atomicBinary(resourceId, ROOT, "bar", headers -> {
            headers.withArchivalGroup(true);
        });

        final var session = sessionFactory.newSession(resourceId);

        write(session, content);
        session.commit();
    }

    private void assertResourceContent(final String content,
                                       final ResourceContent expected,
                                       final ResourceContent actual) {
        if (content == null) {
            assertTrue("content should have been null", actual.getContentStream().isEmpty());
        } else {
            assertEquals(content, toString(actual.getContentStream()));
        }
        assertEquals(expected.getHeaders(), actual.getHeaders());
    }

    private void expectResourceNotFound(final String resourceId, final OcflObjectSession session) {
        try {
            session.readContent(resourceId);
            fail(String.format("Expected resource %s to not exist", resourceId));
        } catch (NotFoundException e) {
            // Expected exception
        }
    }

    private ResourceContent defaultAg() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session, ResourceUtils.ag(DEFAULT_AG_ID, ROOT, "ag"));
        session.commit();
        return session.readContent(DEFAULT_AG_ID);
    }

    private ResourceContent defaultAgBinary() {
        final var session = sessionFactory.newSession(DEFAULT_AG_ID);
        write(session, ResourceUtils.partBinary(DEFAULT_AG_BINARY_ID, DEFAULT_AG_ID, DEFAULT_AG_ID, "bar"));
        session.commit();
        return session.readContent(DEFAULT_AG_BINARY_ID);
    }

    private String toString(final Optional<InputStream> stream) {
        try (var is = stream.get()) {
            return new String(is.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void write(final OcflObjectSession session, final ResourceContent content) {
        session.writeResource(content.getHeaders(), content.getContentStream().orElse(null));
    }

    private void close(final ResourceContent content) {
        try {
            content.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private List<Path> recursiveList(final Path root) {
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void assertHeaders(final List<ResourceHeaders> actual, final ResourceHeaders... expected) {
        final var expectedArray = Arrays.stream(expected).toArray(ResourceHeaders[]::new);
        assertThat(actual, containsInAnyOrder(expectedArray));
    }

    private List<ResourceHeaders> listHeaders(final OcflObjectSession session) {
        return session.streamResourceHeaders().collect(Collectors.toList());
    }

    private void assertVersions(final List<OcflVersionInfo> actual, final String... expected) {
        final var versionNums = actual.stream()
                .map(OcflVersionInfo::getVersionNumber)
                .collect(Collectors.toList());
        assertThat(versionNums, containsInAnyOrder(expected));
    }

    /**
     * Touch the mementoCreatedDate in th related resource so that it has a memento created
     * @param related the resource that's related to the modified resource
     * @param modified the resource that was modified
     * @return the updated headers
     */
    private ResourceContent touch(final ResourceContent related, final ResourceHeaders modified) {
        final var newHeaders = ResourceHeaders.builder(related.getHeaders())
                .withMementoCreatedDate(modified.getLastModifiedDate()).build();
        return new ResourceContent(related.getContentStream(), newHeaders);
    }

    private ResourceContent touch(final ResourceContent related, final ResourceContent modified) {
        return touch(related, modified.getHeaders());
    }
}
