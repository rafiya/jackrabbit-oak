/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.azure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.ResultContinuation;
import com.microsoft.azure.storage.ResultSegment;
import com.microsoft.azure.storage.StorageCredentials;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.StorageUri;
import com.microsoft.azure.storage.blob.BlobListingDetails;
import com.microsoft.azure.storage.blob.CloudBlob;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.azure.storage.blob.CloudBlobDirectory;

import com.microsoft.azure.storage.blob.ListBlobItem;
import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.spi.RepositoryNotReachableException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AzureUtilities {

    public static String SEGMENT_FILE_NAME_PATTERN = "^([0-9a-f]{4})\\.([0-9a-f-]+)$";

    private static final Logger log = LoggerFactory.getLogger(AzureUtilities.class);

    private AzureUtilities() {
    }

    public static String getSegmentFileName(AzureSegmentArchiveEntry indexEntry) {
        return getSegmentFileName(indexEntry.getPosition(), indexEntry.getMsb(), indexEntry.getLsb());
    }

    public static String getSegmentFileName(long offset, long msb, long lsb) {
        return String.format("%04x.%s", offset, new UUID(msb, lsb).toString());
    }

    public static String getName(CloudBlob blob) {
        return Paths.get(blob.getName()).getFileName().toString();
    }

    public static String getName(CloudBlobDirectory directory) {
        return Paths.get(directory.getUri().getPath()).getFileName().toString();
    }

    public static List<CloudBlob> getBlobs(CloudBlobDirectory directory) throws IOException {
        List<CloudBlob> blobList = new ArrayList<>();
        ResultContinuation token = null;
        do {
            ResultSegment<ListBlobItem> result = null;
            IOException lastException = null;
            for (int i = 0; i < 10; i++) {
                try {
                    result = directory.listBlobsSegmented(
                            null,
                            false,
                            EnumSet.of(BlobListingDetails.METADATA),
                            2500,
                            token,
                            null,
                            null);
                } catch (StorageException | URISyntaxException e) {
                    lastException = new IOException(e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        log.warn("Interrupted", e);
                    }
                    continue;
                }
            }
            if (result == null) {
                throw lastException;
            }
            for (ListBlobItem b : result.getResults()) {
                if (b instanceof CloudBlob) {
                    CloudBlob cloudBlob = (CloudBlob) b;
                    blobList.add(cloudBlob);
                }
            }
            token = result.getContinuationToken();
        } while (token != null);

        return blobList;
    }

    public static void readBufferFully(CloudBlob blob, Buffer buffer) throws IOException {
        try {
            blob.download(new ByteBufferOutputStream(buffer));
            buffer.flip();
        } catch (StorageException e) {
            throw new RepositoryNotReachableException(e);
        }
    }

    public static void deleteAllEntries(CloudBlobDirectory directory) throws IOException {
        getBlobs(directory).forEach(b -> {
            try {
                b.deleteIfExists();
            } catch (StorageException e) {
                log.error("Can't delete blob {}", b.getUri().getPath(), e);
            }
        });
    }

    public static CloudBlobDirectory cloudBlobDirectoryFrom(StorageCredentials credentials,
            String uri, String dir) throws URISyntaxException, StorageException {
        StorageUri storageUri = new StorageUri(new URI(uri));
        CloudBlobContainer container = new CloudBlobContainer(storageUri, credentials);

        return container.getDirectoryReference(dir);
    }

    public static CloudBlobDirectory cloudBlobDirectoryFrom(String connection, String containerName,
            String dir) throws InvalidKeyException, URISyntaxException, StorageException {
        CloudStorageAccount cloud = CloudStorageAccount.parse(connection);
        CloudBlobContainer container = cloud.createCloudBlobClient().getContainerReference(containerName);
        container.createIfNotExists();

        return container.getDirectoryReference(dir);
    }

    private static class ByteBufferOutputStream extends OutputStream {

        @NotNull
        private final Buffer buffer;

        public ByteBufferOutputStream(@NotNull Buffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void write(int b) {
            buffer.put((byte)b);
        }

        @Override
        public void write(@NotNull byte[] bytes, int offset, int length) {
            buffer.put(bytes, offset, length);
        }
    }
}


