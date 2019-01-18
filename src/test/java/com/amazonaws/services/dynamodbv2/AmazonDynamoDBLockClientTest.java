/**
 * Copyright 2013-2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * <p>
 * Licensed under the Amazon Software License (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 * <p>
 * http://aws.amazon.com/asl/
 * <p>
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express
 * or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.amazonaws.services.dynamodbv2;

import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.http.HttpStatus;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.metrics.RequestMetricCollector;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemUtils;
import com.amazonaws.services.dynamodbv2.document.internal.InternalUtils;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.DescribeTableResult;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.LockCurrentlyUnavailableException;
import com.amazonaws.services.dynamodbv2.model.LockNotGrantedException;
import com.amazonaws.services.dynamodbv2.model.LockTableDoesNotExistException;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughputExceededException;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.dynamodbv2.model.TableDescription;
import com.amazonaws.services.dynamodbv2.model.TableStatus;
import com.amazonaws.services.dynamodbv2.model.UpdateItemRequest;
import com.amazonaws.services.dynamodbv2.util.LockClientUtils;

import static com.amazonaws.services.dynamodbv2.AmazonDynamoDBLockClient
        .PK_EXISTS_AND_RVN_IS_THE_SAME_AND_IS_RELEASED_CONDITION;
import static com.amazonaws.services.dynamodbv2.AmazonDynamoDBLockClient.RVN_VALUE_EXPRESSION_VARIABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.spy;
import static org.powermock.api.mockito.PowerMockito.when;

/**
 * Unit tests for AmazonDynamoDBLockClient.
 *
 * @author <a href="mailto:amcp@amazon.com">Alexander Patrikalakis</a> 2017-07-13
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest({AmazonDynamoDBLockClient.class, AmazonDynamoDBLockClientOptions.AmazonDynamoDBLockClientOptionsBuilder.class, AmazonDynamoDBLockClientTest.class})
public class AmazonDynamoDBLockClientTest {
    private static final String PARTITION_KEY = "pk";
    private AmazonDynamoDB dynamodb;
    @Before
    public void setup() {
        dynamodb = PowerMockito.mock(AmazonDynamoDB.class);
    }

    @Test
    public void releaseLock_whenRemoveKillSessionMonitorJoinInterrupted_swallowsInterruptedException()
        throws InterruptedException {
        setOwnerNameToUuid();
        Thread thread = spy(new Thread(() -> System.out.println("Running spied thread"), "my-spy-thread"));
        doThrow(new InterruptedException()).when(thread).join();
        //need this otherwise the background thread will start the thread in this frame
        AmazonDynamoDBLockClient lockClient = spy(
            new AmazonDynamoDBLockClient(getLockClientBuilder(threadName -> (runnable -> thread))
                    .build()));
        when(dynamodb.getItem(any())).thenReturn(new GetItemResult());
        LockItem lockItem = lockClient.acquireLock(AcquireLockOptions.builder(PARTITION_KEY)
                .withSessionMonitor(3001,
                    Optional.of(() -> System.out.println("monitored")))
                .withTimeUnit(TimeUnit.MILLISECONDS)
                .build());
        lockClient.releaseLock(lockItem);
    }

    @Test
    public void lockTableExists_whenTableIsUpdating_returnTrue() {
        when(dynamodb.describeTable(any(DescribeTableRequest.class))).thenReturn(new DescribeTableResult().withTable(new TableDescription().withTableStatus(TableStatus.UPDATING)));
        AmazonDynamoDBLockClient lockClient = getLockClient();
        assertTrue(lockClient.lockTableExists());
    }

    @Test
    public void lockTableExists_whenTableIsActive_returnTrue() {
        when(dynamodb.describeTable(any(DescribeTableRequest.class))).thenReturn(new DescribeTableResult().withTable(new TableDescription().withTableStatus(TableStatus.ACTIVE)));
        AmazonDynamoDBLockClient lockClient = getLockClient();
        assertTrue(lockClient.lockTableExists());
    }

    @Test
    public void lockTableExists_whenTableIsDeleting_returnFalse() {
        when(dynamodb.describeTable(any(DescribeTableRequest.class))).thenReturn(new DescribeTableResult().withTable(new TableDescription().withTableStatus(TableStatus.DELETING)));
        AmazonDynamoDBLockClient lockClient = getLockClient();
        assertFalse(lockClient.lockTableExists());
    }

    @Test
    public void lockTableExists_whenTableIsCreating_returnFalse() {
        when(dynamodb.describeTable(any(DescribeTableRequest.class))).thenReturn(new DescribeTableResult().withTable(new TableDescription().withTableStatus(TableStatus.CREATING)));
        AmazonDynamoDBLockClient lockClient = getLockClient();
        assertFalse(lockClient.lockTableExists());
    }

    @Test(expected = LockTableDoesNotExistException.class)
    public void assertLockTableExists_whenTableIsUpdating_returnTrue() {
        when(dynamodb.describeTable(any(DescribeTableRequest.class))).thenReturn(new DescribeTableResult().withTable(new TableDescription().withTableStatus(TableStatus.UPDATING)));
        when(dynamodb.describeTable(any(DescribeTableRequest.class))).thenThrow(new AmazonServiceException("Exception was not a ResourceNotFoundException"));
        AmazonDynamoDBLockClient lockClient = getLockClient();
        lockClient.assertLockTableExists();
    }

    @Test
    public void createLockTableInDynamoDB_whenMetricCollectorIsPresent() {
        RequestMetricCollector collector = PowerMockito.mock(RequestMetricCollector.class);
        CreateDynamoDBTableOptions createTableOptions = spy(CreateDynamoDBTableOptions.builder(dynamodb, new ProvisionedThroughput(1L, 1L), "tableName")
            .withRequestMetricCollector(collector)
            .build());
        AmazonDynamoDBLockClient.createLockTableInDynamoDB(createTableOptions);
        verify(createTableOptions, times(2)).getRequestMetricCollector();
    }

    @Test(expected = LockNotGrantedException.class)
    public void acquireLock_whenLockAlreadyExists_throwLockNotGrantedException() throws InterruptedException {
        setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any())).thenReturn(new GetItemResult());
        when(dynamodb.putItem(any())).thenThrow(new ConditionalCheckFailedException("item existed"));
        client.acquireLock(AcquireLockOptions.builder("asdf").build());
    }

    @Test(expected = LockNotGrantedException.class)
    public void acquireLock_whenProvisionedThroughputExceeds_throwLockNotGrantedException() throws InterruptedException {
        setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any())).thenReturn(new GetItemResult());
        when(dynamodb.putItem(any())).thenThrow(new ProvisionedThroughputExceededException("Provisioned Throughput for the table exceeded"));
        client.acquireLock(AcquireLockOptions.builder("asdf").build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void acquireLock_whenLockAlreadyExists_throwIllegalArgumentException() throws InterruptedException {
        setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClientWithSortKey();
        Map<String, AttributeValue> additionalAttributes = new HashMap<>();
        additionalAttributes.put("sort", new AttributeValue().withS("cool"));
        client.acquireLock(AcquireLockOptions.builder("asdf")
            .withSortKey("sort")
            .withAdditionalAttributes(additionalAttributes).build());
    }

    @Test(expected = LockNotGrantedException.class)
    public void acquireLock_whenLockDoesNotExist_andWhenAcquireOnlyIfLockAlreadyExistsTrue_throwLockNotGrantedException() throws InterruptedException {
        setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any())).thenReturn(new GetItemResult());
        client.acquireLock(AcquireLockOptions.builder("asdf").withAcquireOnlyIfLockAlreadyExists(true).build());
    }

    @Test(expected = LockNotGrantedException.class)
    public void acquireLock_withAcquireOnlyIfLockAlreadyExistsTrue_releasedLockConditionalCheckFailure() throws InterruptedException {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        doAnswer((InvocationOnMock invocation) -> new GetItemResult().withItem(InternalUtils.toAttributeValues(new Item()
                            .withString("customer", "customer1")
                            .withString("ownerName", "foobar")
                            .withString("recordVersionNumber", uuid.toString())
                            .withString("leaseDuration", "1")
                            .withBoolean("isReleased", true))))
                .when(dynamodb).getItem(any());
        when(dynamodb.putItem(any())).thenThrow(new ConditionalCheckFailedException("item existed"));
        client.acquireLock(AcquireLockOptions.builder("asdf").withAcquireOnlyIfLockAlreadyExists(true).build());
    }

    @Test
    public void acquireLock_withAcquireOnlyIfLockAlreadyExists_releasedLockGetsCreated() throws InterruptedException {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any())).thenReturn(new GetItemResult().withItem(InternalUtils.toAttributeValues(new Item()
                .withString("customer", "customer1")
                .withString("ownerName", "foobar")
                .withString("recordVersionNumber", uuid.toString())
                .withString("leaseDuration", "1")
                .withBoolean("isReleased", true))));
        LockItem item = client.acquireLock(AcquireLockOptions.builder("asdf").withAcquireOnlyIfLockAlreadyExists(true).build());
        assertNotNull(item);
        assertEquals("asdf", item.getPartitionKey());
    }

    @Test
    public void acquireLock_whenLockAlreadyExistsAndIsNotReleased_andWhenHaveSleptForMinimumLeaseDurationTime_skipsAddingLeaseDuration()
        throws InterruptedException {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any()))
            .thenReturn(new GetItemResult().withItem(InternalUtils.toAttributeValues(new Item()
                .withString("customer", "customer1")
                .withString("ownerName", "foobar")
                .withString("recordVersionNumber", uuid.toString())
                .withString("leaseDuration", "1")
                )))
            .thenReturn(new GetItemResult().withItem(InternalUtils.toAttributeValues(new Item()
                    .withString("customer", "customer1")
                    .withString("ownerName", "foobar")
                    .withString("recordVersionNumber", "a different uuid")
                    .withString("leaseDuration", "1")
            )))
            .thenReturn(new GetItemResult());
        LockItem item = client.acquireLock(AcquireLockOptions.builder("customer1")
            .withRefreshPeriod(800L)
            .withAdditionalTimeToWaitForLock(1000L)
            .withTimeUnit(TimeUnit.MILLISECONDS)
            .withDeleteLockOnRelease(false).build());
        assertNotNull(item);
    }

    @Test(expected = LockNotGrantedException.class)
    public void acquireLock_withConsistentLockDataTrue_releasedLockConditionalCheckFailure() throws InterruptedException {
    UUID uuid = setOwnerNameToUuid();
    AmazonDynamoDBLockClient client = getLockClient();

        doAnswer((InvocationOnMock invocation) -> new GetItemResult().withItem(InternalUtils.toAttributeValues(new Item()
                .withString("customer", "customer1")
                .withString("ownerName", "foobar")
                .withString("recordVersionNumber", uuid.toString())
                .withString("leaseDuration", "1")
                .withBoolean("isReleased", true))))
                .when(dynamodb).getItem(any());
        when(dynamodb.putItem(any())).thenThrow(new ConditionalCheckFailedException("RVN constraint failed"));
        client.acquireLock(AcquireLockOptions.builder("asdf").withAcquireReleasedLocksConsistently(true).build());
    }

    @Test
    public void acquireLock_withNotUpdateRecordAndConsistentLockDataTrue_releasedLockGetsCreated() throws InterruptedException {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any())).thenReturn(new GetItemResult().withItem(InternalUtils.toAttributeValues(new Item()
                .withString("customer", "customer1")
                .withString("ownerName", "foobar")
                .withString("recordVersionNumber", "a specific rvn")
                .withString("leaseDuration", "1")
                .withBoolean("isReleased", true))));
        LockItem item = client.acquireLock(AcquireLockOptions.builder("asdf").withAcquireReleasedLocksConsistently(true).withUpdateExistingLockRecord
                (false).build());
        assertNotNull(item);
        assertEquals("asdf", item.getPartitionKey());
        ArgumentCaptor<PutItemRequest> putItemCaptor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(dynamodb).putItem(putItemCaptor.capture());
        PutItemRequest putItemRequest = putItemCaptor.getValue();
        assertEquals(PK_EXISTS_AND_RVN_IS_THE_SAME_AND_IS_RELEASED_CONDITION, putItemRequest.getConditionExpression());
        assertEquals("a specific rvn", putItemRequest.getExpressionAttributeValues().get(RVN_VALUE_EXPRESSION_VARIABLE).getS());
    }

    @Test
    public void acquireLock_withUpdateRecordAndConsistentLockDataTrue_releasedLockGetsCreated() throws InterruptedException {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any())).thenReturn(new GetItemResult().withItem(InternalUtils.toAttributeValues(new Item()
                .withString("customer", "customer1")
                .withString("ownerName", "foobar")
                .withString("recordVersionNumber", "a specific rvn")
                .withString("leaseDuration", "1")
                .withBoolean("isReleased", true))));
        LockItem item = client.acquireLock(AcquireLockOptions.builder("asdf").withAcquireReleasedLocksConsistently(true).withUpdateExistingLockRecord
                (true).build());
        assertNotNull(item);
        assertEquals("asdf", item.getPartitionKey());
        ArgumentCaptor<UpdateItemRequest> updateItemCaptor = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamodb).updateItem(updateItemCaptor.capture());
        UpdateItemRequest updateItemRequest = updateItemCaptor.getValue();
        assertEquals(PK_EXISTS_AND_RVN_IS_THE_SAME_AND_IS_RELEASED_CONDITION, updateItemRequest.getConditionExpression());
        assertEquals("a specific rvn", updateItemRequest.getExpressionAttributeValues().get(RVN_VALUE_EXPRESSION_VARIABLE).getS());
    }

    /*
     * Test case that tests that the lock was successfully acquired when the lock does not exist in the table.
     */
    @Test
    public void acquireLock_whenLockNotExists_andSkipBlockingWaitIsTurnedOn()
        throws InterruptedException {
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any())).thenReturn(new GetItemResult());
        LockItem lockItem = client.acquireLock(AcquireLockOptions.builder("customer1")
            .withShouldSkipBlockingWait(true)
            .withDeleteLockOnRelease(false).build());
        Assert.assertNotNull("Failed to get lock item, when the lock is not present in the db", lockItem);
    }

    /*
     * Test case that tests that the lock was successfully acquired when the lock exist in the table and the lock has
     * past the lease duration. This is for cases where the first owner(host) who acquired the lock abruptly died
     * without releasing the lock before the expiry of the lease duration.
     */
    @Test
    public void acquireLock_whenLockExistsAndIsExpired_andSkipBlockingWaitIsTurnedOn()
        throws InterruptedException {
        AmazonDynamoDBLockClient client = getLockClient();
        UUID uuid = setOwnerNameToUuid();
        when(dynamodb.getItem(any()))
            .thenReturn(new GetItemResult().withItem(ItemUtils.toAttributeValues(new Item()
                .withString("customer", "customer1")
                .withString("ownerName", "foobar")
                .withString("recordVersionNumber", uuid.toString())
                .withString("leaseDuration", "1")
                .withBoolean("isReleased", true)
                )))
            .thenReturn(new GetItemResult());
        LockItem lockItem = client.acquireLock(AcquireLockOptions.builder("customer1")
            .withShouldSkipBlockingWait(true)
            .withDeleteLockOnRelease(false).build());
        Assert.assertNotNull("Failed to get lock item, when the lock is not present in the db", lockItem);
    }
    /*
     * Test case for the scenario, where the lock is being held by the first owner and the lock duration has not past the lease duration.
     * In this case, We should expect a LockAlreadyOwnedException when shouldSkipBlockingWait is set.
     */
    @Test(expected = LockCurrentlyUnavailableException.class)
    public void acquireLock_whenLockAlreadyExistsAndIsNotReleased_andSkipBlockingWait_throwsAlreadyOwnedException()
        throws InterruptedException {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        when(dynamodb.getItem(any()))
            .thenReturn(new GetItemResult().withItem(InternalUtils.toAttributeValues(new Item()
                .withString("customer", "customer1")
                .withString("ownerName", "foobar")
                .withString("recordVersionNumber", uuid.toString())
                .withString("leaseDuration", "10001")
                )))
            .thenReturn(new GetItemResult());
        client.acquireLock(AcquireLockOptions.builder("customer1")
            .withShouldSkipBlockingWait(true)
            .withDeleteLockOnRelease(false).build());
    }

    @Test(expected = IllegalArgumentException.class)
    public void sendHeartbeat_whenDeleteDataTrueAndDataNotNull_throwsIllegalArgumentException() {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        LockItem item = new LockItem(client, "a", Optional.empty(), Optional.of(ByteBuffer.wrap("data".getBytes())),
            false, uuid.toString(), 1L, 2L, "rvn", false,
            Optional.empty(), null);
        client.sendHeartbeat(SendHeartbeatOptions.builder(item).withDeleteData(true).withData(ByteBuffer.wrap("data".getBytes())).build());
    }

    @Test(expected = LockNotGrantedException.class)
    public void sendHeartbeat_whenExpired_throwsLockNotGrantedException() {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        long lastUpdatedTimeInMilliseconds = 2l;
        LockItem item = new LockItem(client, "a", Optional.empty(), Optional.of(ByteBuffer.wrap("data".getBytes())),
            false, uuid.toString(), 1L, lastUpdatedTimeInMilliseconds,
            "rvn", false, Optional.empty(), null);
        client.sendHeartbeat(SendHeartbeatOptions.builder(item).withDeleteData(null).withData(ByteBuffer.wrap("data".getBytes())).build());
    }

    @Test(expected = LockNotGrantedException.class)
    public void sendHeartbeat_whenNotExpiredAndDifferentOwner_throwsLockNotGrantedException() {
        setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        long lastUpdatedTimeInMilliseconds = Long.MAX_VALUE;
        LockItem item = new LockItem(client, "a", Optional.empty(), Optional.of(ByteBuffer.wrap("data".getBytes())),
            false, "different owner", 1L, lastUpdatedTimeInMilliseconds,
            "rvn", false, Optional.empty(), null);
        client.sendHeartbeat(SendHeartbeatOptions.builder(item).withDeleteData(null).withData(ByteBuffer.wrap("data".getBytes())).build());
    }

    @Test(expected = LockNotGrantedException.class)
    public void sendHeartbeat_whenNotExpired_andSameOwner_releasedTrue_throwsLockNotGrantedException() {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        long lastUpdatedTimeInMilliseconds = Long.MAX_VALUE;
        LockItem item = new LockItem(client, "a", Optional.empty(), Optional.of(ByteBuffer.wrap("data".getBytes())),
            false, uuid.toString(), 1L, lastUpdatedTimeInMilliseconds,
            "rvn", true, Optional.empty(), null);
        client.sendHeartbeat(SendHeartbeatOptions.builder(item).withDeleteData(null).withData(ByteBuffer.wrap("data".getBytes())).build());
    }

    @Test
    public void sendHeartbeat_whenNotExpired_andSameOwner_releasedFalse_setsRequestMetricCollector() {
        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = getLockClient();
        long lastUpdatedTimeInMilliseconds = Long.MAX_VALUE;
        LockItem item = new LockItem(client, "a", Optional.empty(), Optional.of(ByteBuffer.wrap("data".getBytes())),
            false, uuid.toString(), 1L, lastUpdatedTimeInMilliseconds,
            "rvn", false, Optional.empty(), null);
        client.sendHeartbeat(SendHeartbeatOptions.builder(item)
            .withDeleteData(null)
            .withData(ByteBuffer.wrap("data".getBytes()))
            .withRequestMetricCollector(RequestMetricCollector.NONE)
            .build());
    }

    @Test
    public void sendHeartbeat_whenServiceUnavailable_andHoldLockOnServiceUnavailableFalse_thenDoNotUpdateLookupTime() throws LockNotGrantedException {
        AmazonServiceException serviceUnavailableException = new AmazonServiceException("Service Unavailable.");
        serviceUnavailableException.setStatusCode(HttpStatus.SC_SERVICE_UNAVAILABLE);
        when(dynamodb.updateItem(any())).thenThrow(serviceUnavailableException);

        UUID uuid = setOwnerNameToUuid();
        AmazonDynamoDBLockClient client = new AmazonDynamoDBLockClient(getLockClientBuilder(null).withHoldLockOnServiceUnavailable(false).build());

        long lastUpdatedTimeInMilliseconds = LockClientUtils.INSTANCE.millisecondTime();
        LockItem item = new LockItem(client, "a", Optional.empty(), Optional.of(ByteBuffer.wrap("data".getBytes())),
                false, uuid.toString(), 10000L, lastUpdatedTimeInMilliseconds,
                "rvn", false, Optional.empty(), null);

        AmazonServiceException amazonServiceException = null;
        try {
            client.sendHeartbeat(SendHeartbeatOptions.builder(item).build());
        } catch (AmazonServiceException e) {
            amazonServiceException = e;
        }

        assertEquals(serviceUnavailableException, amazonServiceException);
        assertEquals(lastUpdatedTimeInMilliseconds, item.getLookupTime());
    }

    @Test
    public void sendHeartbeat_whenServiceUnavailable_andHoldLockOnServiceUnavailableTrue_thenUpdateLookupTimeUsingUpdateLookUpTimeMethod() throws LockNotGrantedException, InterruptedException {
        AmazonServiceException serviceUnavailableException = new AmazonServiceException("Service Unavailable.");
        serviceUnavailableException.setStatusCode(HttpStatus.SC_SERVICE_UNAVAILABLE);
        when(dynamodb.updateItem(any())).thenThrow(serviceUnavailableException);

        UUID uuid = setOwnerNameToUuid();
        long leaseDuration = 10000L;
        AmazonDynamoDBLockClient client = new AmazonDynamoDBLockClient(getLockClientBuilder(null)
                .withLeaseDuration(leaseDuration).withHoldLockOnServiceUnavailable(true).build());

        String recordVersionNumber = "rvn";
        long lastUpdatedTimeInMilliseconds = LockClientUtils.INSTANCE.millisecondTime();
        LockItem lockItem = new LockItem(client, "a", Optional.empty(), Optional.of(ByteBuffer.wrap("data".getBytes())),
                false, uuid.toString(), leaseDuration, lastUpdatedTimeInMilliseconds,
                recordVersionNumber, false, Optional.empty(), null);

        // Setting up a spy mock to inspect the method on lockItem object created above
        LockItem lockItemSpy = PowerMockito.spy(lockItem);

        Thread.sleep(1L); // This is to make sure that the lookup time has a higher value
        client.sendHeartbeat(SendHeartbeatOptions.builder(lockItemSpy).build());

        assertTrue(lockItemSpy.getLookupTime() > lastUpdatedTimeInMilliseconds);
        verify(lockItemSpy, times(1)).updateLookUpTime(anyLong());
        verify(lockItemSpy, times(0)).updateRecordVersionNumber(anyString(), anyLong(), anyLong());
    }

    private AmazonDynamoDBLockClient getLockClient() {
        return spy(new AmazonDynamoDBLockClient(
            getLockClientBuilder(null)
                .build()));
    }

    private AmazonDynamoDBLockClient getLockClientWithSortKey() {
        return spy(new AmazonDynamoDBLockClient(
            getLockClientBuilder(null)
                .withSortKeyName("sort")
                .build()));
    }

    private AmazonDynamoDBLockClientOptions.AmazonDynamoDBLockClientOptionsBuilder getLockClientBuilder(Function<String, ThreadFactory> threadFactoryFunction) {
        return new AmazonDynamoDBLockClientOptions.AmazonDynamoDBLockClientOptionsBuilder(
            dynamodb, "locks", null, threadFactoryFunction)
            .withHeartbeatPeriod(3000L)
            .withLeaseDuration(10000L)
            .withTimeUnit(TimeUnit.MILLISECONDS)
            .withPartitionKeyName("customer")
            .withCreateHeartbeatBackgroundThread(false);
    }

    /**
     * Requires power mockito to mock the system and static calls.
     * @return
     */
    public static UUID setOwnerNameToUuid() {
        final UUID uuid = UUID.randomUUID(); //get UUID for use in test
        PowerMockito.mockStatic(UUID.class); //, invocation -> uuid); //mock UUID
        when(UUID.randomUUID()).thenReturn(uuid); //return pregenerated uuid
        PowerMockito.mockStatic(Inet4Address.class);
        try {
            when(Inet4Address.getLocalHost()).thenThrow(new UnknownHostException());
        } catch(UnknownHostException willNotHappenBecauseItsMocked) {
            throw new Error("mock not configured correctly");
        }
        return uuid;
    }
}
