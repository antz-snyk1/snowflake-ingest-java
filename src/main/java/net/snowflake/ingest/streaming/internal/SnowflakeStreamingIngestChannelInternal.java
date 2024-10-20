/*
 * Copyright (c) 2021 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.ingest.streaming.internal;

import static net.snowflake.ingest.utils.Constants.INSERT_THROTTLE_MAX_RETRY_COUNT;
import static net.snowflake.ingest.utils.Constants.LOW_RUNTIME_MEMORY_THRESHOLD_IN_BYTES;
import static net.snowflake.ingest.utils.Constants.MAX_CHUNK_SIZE_IN_BYTES;
import static net.snowflake.ingest.utils.Constants.RESPONSE_SUCCESS;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import net.snowflake.ingest.streaming.InsertValidationResponse;
import net.snowflake.ingest.streaming.OpenChannelRequest;
import net.snowflake.ingest.streaming.SnowflakeStreamingIngestChannel;
import net.snowflake.ingest.utils.Constants;
import net.snowflake.ingest.utils.ErrorCode;
import net.snowflake.ingest.utils.Logging;
import net.snowflake.ingest.utils.SFException;
import net.snowflake.ingest.utils.Utils;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * The first version of implementation for SnowflakeStreamingIngestChannel
 *
 * @param <T> type of column data (Arrow {@link org.apache.arrow.vector.VectorSchemaRoot})
 */
class SnowflakeStreamingIngestChannelInternal<T> implements SnowflakeStreamingIngestChannel {

  private static final Logging logger = new Logging(SnowflakeStreamingIngestChannelInternal.class);

  private final String channelName;
  private final String dbName;
  private final String schemaName;
  private final String tableName;
  private volatile String offsetToken;
  private final AtomicLong rowSequencer;

  // Sequencer for this channel, corresponding to client sequencer at server side because each
  // connection to a channel at server side will be seen as a connection from a new client
  private final Long channelSequencer;

  // Reference to the row buffer
  private final RowBuffer<T> rowBuffer;

  // Indicates whether the channel is still valid
  private volatile boolean isValid;

  // Indicates whether the channel is closed
  private volatile boolean isClosed;

  // Reference to the client that owns this channel
  private final SnowflakeStreamingIngestClientInternal<T> owningClient;

  // Memory allocator
  private final BufferAllocator allocator;

  // Data encryption key
  private final String encryptionKey;

  // Data encryption key id
  private final Long encryptionKeyId;

  // ON_ERROR option for this channel
  private final OpenChannelRequest.OnErrorOption onErrorOption;

  /**
   * Constructor for TESTING ONLY which allows us to set the test mode
   *
   * @param name
   * @param dbName
   * @param schemaName
   * @param tableName
   * @param offsetToken
   * @param channelSequencer
   * @param rowSequencer
   * @param client
   */
  SnowflakeStreamingIngestChannelInternal(
      String name,
      String dbName,
      String schemaName,
      String tableName,
      String offsetToken,
      Long channelSequencer,
      Long rowSequencer,
      SnowflakeStreamingIngestClientInternal<T> client,
      String encryptionKey,
      Long encryptionKeyId,
      OpenChannelRequest.OnErrorOption onErrorOption) {
    this(
        name,
        dbName,
        schemaName,
        tableName,
        offsetToken,
        channelSequencer,
        rowSequencer,
        client,
        encryptionKey,
        encryptionKeyId,
        onErrorOption,
        client.getParameterProvider().getBlobFormatVersion(),
        new RootAllocator());
  }

  /** Default constructor */
  SnowflakeStreamingIngestChannelInternal(
      String name,
      String dbName,
      String schemaName,
      String tableName,
      String offsetToken,
      Long channelSequencer,
      Long rowSequencer,
      SnowflakeStreamingIngestClientInternal<T> client,
      String encryptionKey,
      Long encryptionKeyId,
      OpenChannelRequest.OnErrorOption onErrorOption,
      Constants.BdecVersion bdecVersion,
      BufferAllocator allocator) {
    this.channelName = name;
    this.dbName = dbName;
    this.schemaName = schemaName;
    this.tableName = tableName;
    this.offsetToken = offsetToken;
    this.channelSequencer = channelSequencer;
    this.rowSequencer = new AtomicLong(rowSequencer);
    this.isValid = true;
    this.isClosed = false;
    this.owningClient = client;
    this.allocator = allocator;
    this.rowBuffer = createRowBuffer(bdecVersion);
    this.encryptionKey = encryptionKey;
    this.encryptionKeyId = encryptionKeyId;
    this.onErrorOption = onErrorOption;
    logger.logInfo("Channel={} created for table={}", this.channelName, this.tableName);
  }

  private RowBuffer<T> createRowBuffer(Constants.BdecVersion bdecVersion) {
    // TODO: The circular dependency SnowflakeStreamingIngestChannelInternal <-> RowBuffer
    // (SNOW-657667)
    // can be probably reconsidered
    //noinspection unchecked
    return (RowBuffer<T>)
        new ArrowRowBuffer((SnowflakeStreamingIngestChannelInternal<VectorSchemaRoot>) this);
  }

  /**
   * Get the fully qualified channel name
   *
   * @return fully qualified name of the channel, in the format of
   *     dbName.schemaName.tableName.channelName
   */
  @Override
  public String getFullyQualifiedName() {
    return String.format(
        "%s.%s.%s.%s", this.dbName, this.schemaName, this.tableName, this.channelName);
  }

  /**
   * Get the name of the channel
   *
   * @return name of the channel
   */
  @Override
  public String getName() {
    return this.channelName;
  }

  @Override
  public String getDBName() {
    return this.dbName;
  }

  @Override
  public String getSchemaName() {
    return this.schemaName;
  }

  @Override
  public String getTableName() {
    return this.tableName;
  }

  String getOffsetToken() {
    return this.offsetToken;
  }

  void setOffsetToken(String offsetToken) {
    this.offsetToken = offsetToken;
  }

  Long getChannelSequencer() {
    return this.channelSequencer;
  }

  long incrementAndGetRowSequencer() {
    return this.rowSequencer.incrementAndGet();
  }

  long getRowSequencer() {
    return this.rowSequencer.get();
  }

  String getEncryptionKey() {
    return this.encryptionKey;
  }

  Long getEncryptionKeyId() {
    return this.encryptionKeyId;
  }

  /**
   * Get the fully qualified table name that the channel belongs to
   *
   * @return fully qualified table name, in the format of dbName.schemaName.tableName
   */
  @Override
  public String getFullyQualifiedTableName() {
    return String.format("%s.%s.%s", this.dbName, this.schemaName, this.tableName);
  }

  /**
   * Get all the data needed to build the blob during flush
   *
   * @return a ChannelData object
   */
  ChannelData<T> getData() {
    return this.rowBuffer.flush();
  }

  /** @return a boolean to indicate whether the channel is valid or not */
  @Override
  public boolean isValid() {
    return this.isValid;
  }

  /** Mark the channel as invalid, and release resources */
  void invalidate(String message) {
    this.isValid = false;
    this.rowBuffer.close("invalidate");
    logger.logWarn(
        "Channel is invalidated, name={}, channel sequencer={}, row sequencer={}, message={}",
        getFullyQualifiedName(),
        channelSequencer,
        rowSequencer,
        message);
  }

  /** @return a boolean to indicate whether the channel is closed or not */
  @Override
  public boolean isClosed() {
    return this.isClosed;
  }

  /** Mark the channel as closed */
  void markClosed() {
    this.isClosed = true;
    logger.logInfo(
        "Channel is marked as closed, name={}, channel sequencer={}, row sequencer={}",
        getFullyQualifiedName(),
        channelSequencer,
        rowSequencer);
  }

  /**
   * Flush all data in memory to persistent storage and register with a Snowflake table
   *
   * @param closing whether the flush is called as part of channel closing
   * @return future which will be complete when the flush the data is registered
   */
  CompletableFuture<Void> flush(boolean closing) {
    // Skip this check for closing because we need to set the channel to closed first and then flush
    // in case there is any leftover rows
    if (isClosed() && !closing) {
      throw new SFException(ErrorCode.CLOSED_CHANNEL, getFullyQualifiedName());
    }

    // Simply return if there is no data in the channel, this might not work if we support public
    // flush API since there could a concurrent insert at the same time
    if (this.rowBuffer.getSize() == 0) {
      return CompletableFuture.completedFuture(null);
    }

    return this.owningClient.flush(false);
  }

  /**
   * Close the channel (this will flush in-flight buffered data)
   *
   * @return future which will be complete when the channel is closed
   */
  @Override
  public CompletableFuture<Void> close() {
    checkValidation();

    if (isClosed()) {
      return CompletableFuture.completedFuture(null);
    }

    markClosed();
    return flush(true)
        .thenRunAsync(
            () -> {
              List<SnowflakeStreamingIngestChannelInternal<?>> uncommittedChannels =
                  this.owningClient.verifyChannelsAreFullyCommitted(
                      Collections.singletonList(this));

              this.rowBuffer.close("close");
              this.owningClient.removeChannelIfSequencersMatch(this);

              // Throw an exception if the channel is invalid or has any uncommitted rows
              if (!isValid() || !uncommittedChannels.isEmpty()) {
                throw new SFException(
                    ErrorCode.CHANNELS_WITH_UNCOMMITTED_ROWS,
                    uncommittedChannels.stream()
                        .map(SnowflakeStreamingIngestChannelInternal::getFullyQualifiedName)
                        .collect(Collectors.toList()));
              }
            });
  }

  /**
   * Get the buffer allocator
   *
   * @return the buffer allocator
   */
  BufferAllocator getAllocator() {
    return this.allocator;
  }

  /**
   * Setup the column fields and vectors using the column metadata from the server
   *
   * @param columns
   */
  // TODO: need to verify with the table schema when supporting sub-columns
  void setupSchema(List<ColumnMetadata> columns) {
    logger.logDebug("Setup schema for channel={}, schema={}", getFullyQualifiedName(), columns);
    this.rowBuffer.setupSchema(columns);
  }

  /**
   * --------------------------------------------------------------------------------------------
   * Insert one row into the channel
   * --------------------------------------------------------------------------------------------
   */

  /**
   * The row is represented using Map where the key is column name and the value is a row of data
   *
   * @param row object data to write
   * @param offsetToken offset of given row, used for replay in case of failures
   * @return insert response that possibly contains errors because of insertion failures
   * @throws SFException when the channel is invalid or closed
   */
  @Override
  public InsertValidationResponse insertRow(Map<String, Object> row, String offsetToken) {
    return insertRows(Collections.singletonList(row), offsetToken);
  }

  /**
   * --------------------------------------------------------------------------------------------
   * Insert a batch of rows into the channel
   * --------------------------------------------------------------------------------------------
   */

  /**
   * Each row is represented using Map where the key is column name and the value is a row of data
   *
   * @param rows object data to write
   * @param offsetToken offset of last row in the row-set, used for replay in case of failures
   * @return insert response that possibly contains errors because of insertion failures
   * @throws SFException when the channel is invalid or closed
   */
  @Override
  public InsertValidationResponse insertRows(
      Iterable<Map<String, Object>> rows, String offsetToken) {
    throttleInsertIfNeeded(Runtime.getRuntime());
    checkValidation();

    if (isClosed()) {
      throw new SFException(ErrorCode.CLOSED_CHANNEL, getFullyQualifiedName());
    }

    InsertValidationResponse response = this.rowBuffer.insertRows(rows, offsetToken);

    // Start flush task if the chunk size reaches a certain size
    // TODO: Checking table/chunk level size reduces throughput a lot, we may want to check it only
    // if a large number of rows are inserted
    if (this.rowBuffer.getSize() >= MAX_CHUNK_SIZE_IN_BYTES) {
      this.owningClient.setNeedFlush();
    }

    return response;
  }

  /** Collect the row size from row buffer if required */
  void collectRowSize(float rowSize) {
    if (this.owningClient.inputThroughput != null) {
      this.owningClient.inputThroughput.mark((long) rowSize);
    }
  }

  /**
   * Get the latest committed offset token from Snowflake
   *
   * @return the latest committed offset token
   */
  @Override
  public String getLatestCommittedOffsetToken() {
    checkValidation();

    ChannelsStatusResponse.ChannelStatusResponseDTO response =
        this.owningClient.getChannelsStatus(Collections.singletonList(this)).getChannels().get(0);

    if (response.getStatusCode() != RESPONSE_SUCCESS) {
      throw new SFException(ErrorCode.CHANNEL_STATUS_INVALID, getName(), response.getStatusCode());
    }

    return response.getPersistedOffsetToken();
  }

  /** Check whether we need to throttle the insertRows API */
  void throttleInsertIfNeeded(Runtime runtime) {
    int retry = 0;
    long insertThrottleIntervalInMs =
        this.owningClient.getParameterProvider().getInsertThrottleIntervalInMs();
    while ((hasLowRuntimeMemory(runtime)
            || (this.owningClient.getFlushService() != null
                && this.owningClient.getFlushService().throttleDueToQueuedFlushTasks()))
        && retry < INSERT_THROTTLE_MAX_RETRY_COUNT) {
      try {
        Thread.sleep(insertThrottleIntervalInMs);
        retry++;
      } catch (InterruptedException e) {
        throw new SFException(ErrorCode.INTERNAL_ERROR, "Insert throttle get interrupted");
      }
    }
  }

  /** Check whether we have a low runtime memory condition */
  private boolean hasLowRuntimeMemory(Runtime runtime) {
    int insertThrottleThresholdInPercentage =
        this.owningClient.getParameterProvider().getInsertThrottleThresholdInPercentage();
    boolean hasLowRuntimeMemory =
        runtime.freeMemory() < LOW_RUNTIME_MEMORY_THRESHOLD_IN_BYTES
            && runtime.freeMemory() * 100 / runtime.totalMemory()
                < insertThrottleThresholdInPercentage;
    if (hasLowRuntimeMemory) {
      logger.logWarn(
          "Throttled due to memory pressure, client={}, channel={}.",
          this.owningClient.getName(),
          getFullyQualifiedName());
      Utils.showMemory();
    }
    return hasLowRuntimeMemory;
  }

  /** Check whether the channel is still valid, cleanup and throw an error if not */
  private void checkValidation() {
    if (!isValid()) {
      this.owningClient.removeChannelIfSequencersMatch(this);
      this.rowBuffer.close("checkValidation");
      throw new SFException(ErrorCode.INVALID_CHANNEL, getFullyQualifiedName());
    }
  }

  OpenChannelRequest.OnErrorOption getOnErrorOption() {
    return this.onErrorOption;
  }

  /** Returns underlying channel's row buffer implementation. */
  RowBuffer<T> getRowBuffer() {
    return rowBuffer;
  }
}
