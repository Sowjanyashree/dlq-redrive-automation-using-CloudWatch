package com.sqsmessage;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SNSEvent;
import org.slf4j.Logger;
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

    private static final Logger logger = (Logger) java.util.logging.Logger.getLogger(String.valueOf(MessageRedrive.class));
    private static final int BATCH_SIZE = 10;

    private final SqsClient sqsClient;
    private final String dlqUrl;
    private final String sourceQueueUrl;

    public MessageRedrive() {
        String region = System.getenv("AWS_REGION");
        this.dlqUrl = System.getenv("DLQ_URL");
        this.sourceQueueUrl = System.getenv("SOURCE_QUEUE_URL");

        if (region == null || region.isEmpty()) {
            logger.error("AWS_REGION environment variable is not set.");
            throw new IllegalArgumentException("AWS_REGION environment variable is required.");
        }
        if (this.dlqUrl == null || this.dlqUrl.isEmpty()) {
            logger.error("DLQ_URL environment variable is not set.");
            throw new IllegalArgumentException("DLQ_URL environment variable is required.");
        }
        if (this.sourceQueueUrl == null || this.sourceQueueUrl.isEmpty()) {
            logger.error("SOURCE_QUEUE_URL environment variable is not set.");
            throw new IllegalArgumentException("SOURCE_QUEUE_URL environment variable is required.");
        }

        this.sqsClient = SqsClient.builder()
                .region(Region.of(region))
                .build();
    }

    @Override
    public Void handleRequest(SNSEvent event, Context context) {
        logger.info("Received SNS event with {} records.", event.getRecords().size());

        for (SNSEvent. SNSRecord SNSRecord: event.getRecords()) {
            logger.info("SNS Message ID: {}", SNSRecord.getSNS().getMessageId());
            logger.info("SNS Subject: {}", SNSRecord.getSNS().getSubject());
            logger.info("SNS Message: {}", SNSRecord.getSNS().getMessageId());
        }

        logger.info("Attempting to redrive messages from DLQ: {} to MainQueue: {}", dlqUrl, sourceQueueUrl);

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
                    logger.info("No more messages found in the Dead Letter Queue.");
                    break;
                }

                logger.info("Found {} messages in DLQ. Preparing to redrive this batch...", dlqMessages.size());

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
                    logger.error("Some messages failed to send to the Main Queue in this batch: {}", sendBatchResponse.failed());
                } else {
                    logger.info("Successfully sent {} messages to MainQueue in this batch.", sendEntries.size());
                }

                DeleteMessageBatchRequest deleteBatchRequest = DeleteMessageBatchRequest.builder()
                        .queueUrl(dlqUrl)
                        .entries(deleteEntries)
                        .build();

                DeleteMessageBatchResponse deleteBatchResponse = sqsClient.deleteMessageBatch(deleteBatchRequest);

                if (!deleteBatchResponse.failed().isEmpty()) {
                    logger.error("Some messages failed to delete from Dead Letter Queue in this batch: {}", deleteBatchResponse.failed());
                } else {
                    logger.info("Successfully deleted {} messages from DeadLetterQueue in this batch.", deleteEntries.size());
                }

                totalMessagesRedriven += dlqMessages.size();
            }

            logger.info("Completed redriving process. Total messages redriven: {}", totalMessagesRedriven);

        } catch (SqsException e) {
            logger.error("SQS Error during redrive: {}. Details: {}", e.getMessage(), e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : "N/A", e);
            throw new RuntimeException("Failed to redrive messages due to SQS error.", e);
        } catch (SdkClientException e) {
            logger.warn("AWS SDK Client Error during redrive: {}. Details: ", e.getMessage(), e);
            throw new RuntimeException("Failed to redrive messages due to AWS SDK client error.", e);
        } catch (Exception e) {
            //logger.("AWS SDK Client Error during redrive: {}. Details: ", e.getMessage(), e);
            logger.warn("An unexpected error occurred during redrive: {}. Details:" ,  e.getMessage() , e);
            throw new RuntimeException("An unexpected error occurred during message redrive.", e);
        }

        return null;
    }
}
