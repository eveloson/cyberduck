package ch.cyberduck.core.ftp;

import ch.cyberduck.core.Credentials;
import ch.cyberduck.core.DisabledCancelCallback;
import ch.cyberduck.core.DisabledHostKeyCallback;
import ch.cyberduck.core.DisabledListProgressListener;
import ch.cyberduck.core.DisabledLoginCallback;
import ch.cyberduck.core.DisabledPasswordStore;
import ch.cyberduck.core.DisabledTranscriptListener;
import ch.cyberduck.core.Host;
import ch.cyberduck.core.Path;
import ch.cyberduck.core.PathCache;
import ch.cyberduck.core.exception.AccessDeniedException;
import ch.cyberduck.core.features.Delete;
import ch.cyberduck.core.features.Find;
import ch.cyberduck.core.io.StreamCopier;
import ch.cyberduck.core.shared.DefaultAttributesFeature;
import ch.cyberduck.core.shared.DefaultFindFeature;
import ch.cyberduck.core.shared.DefaultHomeFinderService;
import ch.cyberduck.core.shared.DefaultTouchFeature;
import ch.cyberduck.core.transfer.TransferStatus;
import ch.cyberduck.test.IntegrationTest;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * @version $Id$
 */
@Category(IntegrationTest.class)
public class FTPWriteFeatureTest {

    @Test
    public void testReadWrite() throws Exception {
        final Host host = new Host(new FTPTLSProtocol(), "test.cyberduck.ch", new Credentials(
                System.getProperties().getProperty("ftp.user"), System.getProperties().getProperty("ftp.password")
        ));
        final FTPSession session = new FTPSession(host);
        session.open(new DisabledHostKeyCallback(), new DisabledTranscriptListener());
        session.login(new DisabledPasswordStore(), new DisabledLoginCallback(), new DisabledCancelCallback());
        final TransferStatus status = new TransferStatus();
        final byte[] content = "test".getBytes("UTF-8");
        status.setLength(content.length);
        final Path test = new Path(session.workdir(), UUID.randomUUID().toString(), EnumSet.of(Path.Type.file));
        final OutputStream out = new FTPWriteFeature(session).write(test, status);
        assertNotNull(out);
        new StreamCopier(new TransferStatus(), new TransferStatus()).transfer(new ByteArrayInputStream(content), out);
        IOUtils.closeQuietly(out);
        assertTrue(session.getFeature(Find.class).find(test));
        assertEquals(content.length, session.list(test.getParent(), new DisabledListProgressListener()).get(test).attributes().getSize());
        assertEquals(content.length, new FTPWriteFeature(session).append(test, status.getLength(), PathCache.empty()).size, 0L);
        {
            final InputStream in = new FTPReadFeature(session).read(test, new TransferStatus().length(content.length));
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(content.length);
            new StreamCopier(status, status).transfer(in, buffer);
            IOUtils.closeQuietly(in);
            assertArrayEquals(content, buffer.toByteArray());
        }
        {
            final byte[] buffer = new byte[content.length - 1];
            final InputStream in = new FTPReadFeature(session).read(test, new TransferStatus().length(content.length).append(true).skip(1L));
            IOUtils.readFully(in, buffer);
            IOUtils.closeQuietly(in);
            final byte[] reference = new byte[content.length - 1];
            System.arraycopy(content, 1, reference, 0, content.length - 1);
            assertArrayEquals(reference, buffer);
        }
        new FTPDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.Callback() {
            @Override
            public void delete(final Path file) {
            }
        });
        session.close();
    }

    @Test
    public void testWriteContentRange() throws Exception {
        final Host host = new Host(new FTPTLSProtocol(), "test.cyberduck.ch", new Credentials(
                System.getProperties().getProperty("ftp.user"), System.getProperties().getProperty("ftp.password")
        ));
        final FTPSession session = new FTPSession(host);
        session.open(new DisabledHostKeyCallback(), new DisabledTranscriptListener());
        session.login(new DisabledPasswordStore(), new DisabledLoginCallback(), new DisabledCancelCallback());
        final FTPWriteFeature feature = new FTPWriteFeature(session);
        final Path test = new Path(session.workdir(), UUID.randomUUID().toString(), EnumSet.of(Path.Type.file));
        final byte[] content = RandomUtils.nextBytes(64000);
        {
            final TransferStatus status = new TransferStatus();
            status.setLength(1024L);
            final OutputStream out = feature.write(test, status);
            // Write first 1024
            new StreamCopier(status, status).withOffset(0L).withLimit(status.getLength()).transfer(new ByteArrayInputStream(content), out);
            out.close();
        }
        assertTrue(new DefaultFindFeature(session).find(test));
        assertEquals(1024L, new DefaultAttributesFeature(session).find(test).getSize());
        {
            // Remaining chunked transfer with offset
            final TransferStatus status = new TransferStatus();
            status.setLength(content.length - 1024L);
            status.setOffset(1024L);
            status.setAppend(true);
            final OutputStream out = feature.write(test, status);
            new StreamCopier(status, status).withOffset(status.getOffset()).withLimit(status.getLength()).transfer(new ByteArrayInputStream(content), out);
            out.close();
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        IOUtils.copy(new FTPReadFeature(session).read(test, new TransferStatus().length(content.length)), out);
        assertArrayEquals(content, out.toByteArray());
        new FTPDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.Callback() {
            @Override
            public void delete(final Path file) {
            }
        });
    }

    @Test
    @Ignore
    public void testWriteRangeEndFirst() throws Exception {
        final Host host = new Host(new FTPTLSProtocol(), "test.cyberduck.ch", new Credentials(
                System.getProperties().getProperty("ftp.user"), System.getProperties().getProperty("ftp.password")
        ));
        final FTPSession session = new FTPSession(host);
        session.open(new DisabledHostKeyCallback(), new DisabledTranscriptListener());
        session.login(new DisabledPasswordStore(), new DisabledLoginCallback(), new DisabledCancelCallback());
        final FTPWriteFeature feature = new FTPWriteFeature(session);
        final Path test = new Path(session.workdir(), UUID.randomUUID().toString(), EnumSet.of(Path.Type.file));
        final byte[] content = RandomUtils.nextBytes(2048);
        {
            // Write end of file first
            final TransferStatus status = new TransferStatus();
            status.setLength(1024L);
            status.setOffset(1024L);
            status.setAppend(true);
            final OutputStream out = feature.write(test, status);
            new StreamCopier(status, status).withOffset(status.getOffset()).withLimit(status.getLength()).transfer(new ByteArrayInputStream(content), out);
            out.close();
        }
        assertTrue(new DefaultFindFeature(session).find(test));
        assertEquals(content.length, new DefaultAttributesFeature(session).find(test).getSize());
        {
            // Write beginning of file up to the last chunk
            final TransferStatus status = new TransferStatus();
            status.setExists(true);
            status.setOffset(0L);
            status.setLength(1024L);
            status.setAppend(true);
            final OutputStream out = feature.write(test, status);
            new StreamCopier(status, status).withOffset(status.getOffset()).withLimit(status.getLength()).transfer(new ByteArrayInputStream(content), out);
            out.close();
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(content.length);
        IOUtils.copy(new FTPReadFeature(session).read(test, new TransferStatus().length(content.length)), out);
        assertArrayEquals(content, out.toByteArray());
        assertTrue(new DefaultFindFeature(session).find(test));
        assertEquals(content.length, new DefaultAttributesFeature(session).find(test).getSize());
        new FTPDeleteFeature(session).delete(Collections.singletonList(test), new DisabledLoginCallback(), new Delete.Callback() {
            @Override
            public void delete(final Path file) {
            }
        });
    }


    @Test(expected = AccessDeniedException.class)
    public void testWriteNotFound() throws Exception {
        final Host host = new Host(new FTPTLSProtocol(), "test.cyberduck.ch", new Credentials(
                System.getProperties().getProperty("ftp.user"), System.getProperties().getProperty("ftp.password")
        ));
        final FTPSession session = new FTPSession(host);
        session.open(new DisabledHostKeyCallback(), new DisabledTranscriptListener());
        session.login(new DisabledPasswordStore(), new DisabledLoginCallback(), new DisabledCancelCallback());
        final Path test = new Path(new DefaultHomeFinderService(session).find().getAbsolute() + "/nosuchdirectory/" + UUID.randomUUID().toString(), EnumSet.of(Path.Type.file));
        new FTPWriteFeature(session).write(test, new TransferStatus());
    }

    @Test
    public void testAppend() throws Exception {
        final Host host = new Host(new FTPTLSProtocol(), "test.cyberduck.ch", new Credentials(
                System.getProperties().getProperty("ftp.user"), System.getProperties().getProperty("ftp.password")
        ));
        final FTPSession session = new FTPSession(host);
        session.open(new DisabledHostKeyCallback(), new DisabledTranscriptListener());
        session.login(new DisabledPasswordStore(), new DisabledLoginCallback(), new DisabledCancelCallback());
        assertEquals(false, new FTPWriteFeature(session).append(
                new Path(session.workdir(), UUID.randomUUID().toString(), EnumSet.of(Path.Type.file)), 0L, PathCache.empty()).append);
        final Path f = new Path(session.workdir(), UUID.randomUUID().toString(), EnumSet.of(Path.Type.file));
        new DefaultTouchFeature(session).touch(f);
        assertEquals(true, new FTPWriteFeature(session).append(f, 0L, PathCache.empty()).append);
        new FTPDeleteFeature(session).delete(Collections.singletonList(f), new DisabledLoginCallback(), new Delete.Callback() {
            @Override
            public void delete(final Path file) {
            }
        });
    }
}