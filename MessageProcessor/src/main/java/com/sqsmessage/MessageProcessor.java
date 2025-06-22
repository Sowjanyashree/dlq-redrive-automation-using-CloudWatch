package com.sqsmessage;



import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Lambda function that processes messages from an SQS queue.
 * It reads messages, processes them, and returns a batch response indicating
 * which messages were successfully processed and which failed.
 */
public class MessageProcessor implements RequestHandler<SQSEvent, SQSBatchResponse> {

    //private static Logger LoggerFactory;
    private static final Logger logger = Logger.getLogger(String.valueOf(MessageProcessor.class));

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        logger.info("Received SQS event with {} messages.");

        List<SQSBatchResponse.BatchItemFailure> batchItemFailures = new ArrayList<>();

        for (SQSEvent.SQSMessage message : event.getRecords()) {
            String messageId = message.getMessageId();
            String messageBody = message.getBody();
            logger.info("Processing messageId: {}, body: {}");

            try {
                // Simulate message processing.
                // In a real application, you would add your business logic here.
                // For demonstration, we'll simulate a failure for messages containing "fail".
                if (messageBody.contains("fail")) {
                    logger.log(Level.parse("Simulating failure for messageId: {}"), messageId);
                    throw new RuntimeException("Simulated processing failure");
                }

                logger.info("Successfully processed messageId: {}");

            } catch (Exception e) {
                logger.log(Level.parse("Error processing messageId: {}. Reason: {}"), messageId, e.getMessage());
                // Add the message ID to batchItemFailures to indicate it should not be deleted from the queue.
                batchItemFailures.add(new SQSBatchResponse.BatchItemFailure(messageId));
            }
        }

        // Return the batch response. Messages not in batchItemFailures are considered successful
        // and will be deleted from the queue by SQS.
        return new SQSBatchResponse(batchItemFailures);
    }
}
