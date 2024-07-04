/*
 * Copyright (C) 2023 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.catalog.files.s3;

import static com.google.common.base.Preconditions.checkArgument;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.projectnessie.catalog.files.s3.S3Utils.isS3scheme;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.projectnessie.catalog.files.api.BackendThrottledException;
import org.projectnessie.catalog.files.api.NonRetryableException;
import org.projectnessie.catalog.files.api.ObjectIO;
import org.projectnessie.storage.uri.StorageUri;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3ObjectIO implements ObjectIO {
  private static final Logger LOGGER = LoggerFactory.getLogger(S3ObjectIO.class);

  private final S3ClientSupplier s3clientSupplier;
  private final Clock clock;

  public S3ObjectIO(S3ClientSupplier s3clientSupplier, Clock clock) {
    this.s3clientSupplier = s3clientSupplier;
    this.clock = clock;
  }

  @Override
  public void ping(StorageUri uri) throws IOException {
    S3Client s3client = s3clientSupplier.getClient(uri);
    try {
      s3client.headBucket(b -> b.bucket(uri.requiredAuthority()));
    } catch (RuntimeException e) {
      throw new IOException(e);
    }
  }

  @Override
  public InputStream readObject(StorageUri uri) throws IOException {
    checkArgument(uri != null, "Invalid location: null");
    String scheme = uri.scheme();
    checkArgument(isS3scheme(scheme), "Invalid S3 scheme: %s", uri);

    S3Client s3client = s3clientSupplier.getClient(uri);

    try {
      return s3client.getObject(
          GetObjectRequest.builder()
              .bucket(uri.requiredAuthority())
              .key(withoutLeadingSlash(uri))
              .build());
    } catch (SdkServiceException e) {
      if (e.isThrottlingException()) {
        throw new BackendThrottledException(
            clock
                .instant()
                .plus(s3clientSupplier.s3config().retryAfter().orElse(Duration.of(10, SECONDS))),
            "S3 throttled",
            e);
      }
      throw new NonRetryableException(e);
    }
  }

  @Override
  public OutputStream writeObject(StorageUri uri) {
    checkArgument(uri != null, "Invalid location: null");
    checkArgument(isS3scheme(uri.scheme()), "Invalid S3 scheme: %s", uri);

    return new ByteArrayOutputStream() {
      private final AtomicBoolean closed = new AtomicBoolean();

      @Override
      public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
          super.close();

          S3Client s3client = s3clientSupplier.getClient(uri);

          s3client.putObject(
              PutObjectRequest.builder()
                  .bucket(uri.requiredAuthority())
                  .key(withoutLeadingSlash(uri))
                  .build(),
              RequestBody.fromBytes(toByteArray()));
        }
      }
    };
  }

  @Override
  public void deleteObjects(List<StorageUri> uris) {
    Map<String, List<StorageUri>> bucketToUris =
        uris.stream().collect(Collectors.groupingBy(StorageUri::requiredAuthority));

    for (Map.Entry<String, List<StorageUri>> bucketDeletes : bucketToUris.entrySet()) {
      String bucket = bucketDeletes.getKey();
      S3Client s3client = s3clientSupplier.getClient(bucket);

      List<StorageUri> locations = bucketDeletes.getValue();
      List<ObjectIdentifier> objectIdentifiers =
          locations.stream()
              .map(S3ObjectIO::withoutLeadingSlash)
              .map(key -> ObjectIdentifier.builder().key(key).build())
              .collect(Collectors.toList());

      DeleteObjectsRequest.Builder deleteObjectsRequest =
          DeleteObjectsRequest.builder()
              .bucket(bucket)
              .delete(Delete.builder().objects(objectIdentifiers).build());

      s3client.deleteObjects(deleteObjectsRequest.build());
    }
  }

  private static String withoutLeadingSlash(StorageUri uri) {
    String path = uri.requiredPath();
    return path.startsWith("/") ? path.substring(1) : path;
  }
}
