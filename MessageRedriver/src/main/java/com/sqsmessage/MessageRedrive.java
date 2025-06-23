package com.sqsmessage;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lambda function that redrives messages from the DeadLetterQueue back to the MainQueue.
 * It is triggered by an SNS notification, which originates from a CloudWatch Alarm monitoring the DLQ.
 */
public class MessageRedrive implements RequestHandler<SNSEvent, Void> {

    private static final int BATCH_SIZE = 10;

    private final SqsClient sqsClient;
    private final String dlqUrl;
    private final String sourceQueueUrl;

    public MessageRedrive() {
        String region = System.getenv("AWS_REGION");
        this.dlqUrl = System.getenv("DLQ_URL");
        this.sourceQueueUrl = System.getenv("SOURCE_QUEUE_URL");

        if (region == null || region.isEmpty()) {
            throw new IllegalArgumentException("AWS_REGION environment variable is required.");
        }
        if (this.dlqUrl == null || this.dlqUrl.isEmpty()) {
            throw new IllegalArgumentException("DLQ_URL environment variable is required.");
        }
        if (this.sourceQueueUrl == null || this.sourceQueueUrl.isEmpty()) {
            throw new IllegalArgumentException("SOURCE_QUEUE_URL environment variable is required.");
        }

        this.sqsClient = SqsClient.builder()
                .region(Region.of(region))
                .build();
    }

    @Override
    public Void handleRequest(SNSEvent event, Context context) {
        context.getLogger().log("Redrive Lambda triggered by SNS event with " + event.getRecords().size() + " records.\n");

        for (SNSEvent.SNSRecord snsRecord : event.getRecords()) {
            context.getLogger().log("SNS Message ID: " + snsRecord.getSNS().getMessageId() + "\n");
            context.getLogger().log("SNS Subject: " + snsRecord.getSNS().getSubject() + "\n");
            context.getLogger().log("SNS Message: " + snsRecord.getSNS().getMessage() + "\n");
        }

        context.getLogger().log("Starting redrive process from DLQ: " + dlqUrl + " to MainQueue: " + sourceQueueUrl + "\n");

        try {
            int totalMessagesRedriven = 0;

            while (true) {
                ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                        .queueUrl(dlqUrl)
                        .maxNumberOfMessages(BATCH_SIZE)
                        .visibilityTimeout(30)
                        .waitTimeSeconds(5)
                        .build();

                ReceiveMessageResponse receiveResponse = sqsClient.receiveMessage(receiveRequest);
                List<Message> dlqMessages = receiveResponse.messages();

                if (dlqMessages.isEmpty()) {
                    context.getLogger().log("No more messages found in the Dead Letter Queue.\n");
                    break;
                }

                context.getLogger().log("Found " + dlqMessages.size() + " messages in DLQ. Redriving this batch...\n");

                List<SendMessageBatchRequestEntry> sendEntries = new ArrayList<>();
                List<DeleteMessageBatchRequestEntry> deleteEntries = new ArrayList<>();

                for (Message dlqMessage : dlqMessages) {
                    sendEntries.add(SendMessageBatchRequestEntry.builder()
                            .id(UUID.randomUUID().toString())
                            .messageBody(dlqMessage.body())
                            .build());

                    deleteEntries.add(DeleteMessageBatchRequestEntry.builder()
                            .id(dlqMessage.messageId())
                            .receiptHandle(dlqMessage.receiptHandle())
                            .build());
                }

                SendMessageBatchRequest sendBatchRequest = SendMessageBatchRequest.builder()
                        .queueUrl(sourceQueueUrl)
                        .entries(sendEntries)
                        .build();

                SendMessageBatchResponse sendBatchResponse = sqsClient.sendMessageBatch(sendBatchRequest);

                if (!sendBatchResponse.failed().isEmpty()) {
                    context.getLogger().log("Some messages failed to send to Main Queue: " + sendBatchResponse.failed() + "\n");
                } else {
                    context.getLogger().log("Successfully sent " + sendEntries.size() + " messages to MainQueue.\n");
                }

                DeleteMessageBatchRequest deleteBatchRequest = DeleteMessageBatchRequest.builder()
                        .queueUrl(dlqUrl)
                        .entries(deleteEntries)
                        .build();

                DeleteMessageBatchResponse deleteBatchResponse = sqsClient.deleteMessageBatch(deleteBatchRequest);

                if (!deleteBatchResponse.failed().isEmpty()) {
                    context.getLogger().log("Some messages failed to delete from DLQ: " + deleteBatchResponse.failed() + "\n");
                } else {
                    context.getLogger().log("Successfully deleted " + deleteEntries.size() + " messages from DeadLetterQueue.\n");
                }

                totalMessagesRedriven += dlqMessages.size();
            }

            context.getLogger().log("Redrive process completed successfully. Total messages redriven: " + totalMessagesRedriven + "\n");

        } catch (SqsException e) {
            context.getLogger().log("SQS Error during redrive: " + e.getMessage() + "\n");
            throw new RuntimeException("SQS Exception occurred: " + e.getMessage(), e);
        } catch (SdkClientException e) {
            context.getLogger().log("AWS SDK Client Error during redrive: " + e.getMessage() + "\n");
            throw new RuntimeException("AWS SDK Client Exception occurred: " + e.getMessage(), e);
        } catch (Exception e) {
            context.getLogger().log("Unexpected error during redrive: " + e.getMessage() + "\n");
            throw new RuntimeException("Unexpected exception occurred: " + e.getMessage(), e);
        }

        return null;
    }
}
