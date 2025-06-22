# Automated SQS Dead Letter Queue Processing with CloudWatch Integration

This project implements an automated message processing system using AWS SQS with dead-letter queue (DLQ) handling and automatic message redriving capabilities. It provides reliable message processing with automatic error recovery through CloudWatch monitoring and Lambda-based reprocessing.

The system consists of two main components: a message processor that handles incoming messages with retry capability, and a message redriver that automatically recovers failed messages from the DLQ when triggered by CloudWatch alarms. This architecture ensures message processing reliability while minimizing message loss and manual intervention.

## Repository Structure
```
.
├── MessageProcessor/                 # Main message processing Lambda function
│   ├── pom.xml                      # Maven configuration for message processor
│   └── src/main/java/              
│       └── MessageProcessor.java     # Core message processing logic
├── MessageRedriver/                  # DLQ message recovery Lambda function
│   ├── pom.xml                      # Maven configuration for message redriver
│   └── src/main/java/
│       └── MessageRedrive.java      # DLQ message recovery implementation
├── template.yaml                     # AWS SAM infrastructure definition
├── samconfig.toml                    # SAM CLI configuration
└── pom.xml                          # Parent Maven project configuration
```

## Usage Instructions
### Prerequisites
- Java Development Kit (JDK) 17 or later
- Apache Maven 3.8.x or later
- AWS SAM CLI
- AWS CLI configured with appropriate credentials
- AWS account with permissions to create:
  - Lambda functions
  - SQS queues
  - CloudWatch alarms
  - SNS topics
  - IAM roles

### Installation

1. Clone the repository:
```bash
git clone <repository-url>
cd dlq-redrive-automation-using-CloudWatch
```

2. Build the project:
```bash
mvn clean package
```

3. Deploy using SAM:
```bash
sam build
sam deploy --guided
```

During the guided deployment, you'll need to:
- Confirm the stack name (default: dlq-redrive-automation-using-CloudWatch)
- Select the AWS region
- Allow SAM to create IAM roles
- Confirm the deployment changes

### Quick Start

1. Send a test message to the MainQueue:
```bash
aws sqs send-message \
    --queue-url <MAIN_QUEUE_URL> \
    --message-body "Test message"
```

2. Monitor message processing:
```bash
aws cloudwatch get-metric-statistics \
    --namespace AWS/SQS \
    --metric-name ApproximateNumberOfMessagesVisible \
    --dimensions Name=QueueName,Value=MainQueue \
    --start-time $(date -u -v-1H +%FT%TZ) \
    --end-time $(date -u +%FT%TZ) \
    --period 300 \
    --statistics Sum
```

### More Detailed Examples

Testing message failure and DLQ processing:
```bash
# Send a message that will fail processing
aws sqs send-message \
    --queue-url <MAIN_QUEUE_URL> \
    --message-body "Test message with fail"

# Check DLQ messages after processing attempts
aws sqs get-queue-attributes \
    --queue-url <DLQ_URL> \
    --attribute-names ApproximateNumberOfMessages
```

### Troubleshooting

Common issues and solutions:

1. Message Processing Failures
- Problem: Messages repeatedly failing processing
- Solution: Check CloudWatch logs at `/aws/lambda/MessageProcessorFunction`
```bash
aws logs get-log-events \
    --log-group-name /aws/lambda/MessageProcessorFunction \
    --log-stream-name <latest-stream>
```

2. DLQ Redrive Issues
- Problem: Messages not being redriven from DLQ
- Solution: Verify CloudWatch alarm configuration
```bash
aws cloudwatch describe-alarms \
    --alarm-names DLQMessageAlarm
```

## Data Flow
The system processes messages through a main queue with automatic dead-letter queue handling and reprocessing capabilities.

```ascii
                     [MainQueue]
                         │
                         ▼
              [MessageProcessor Lambda]
                         │
           ┌────────────┴─────────────┐
           │                          │
    [Success Path]              [Failure Path]
           │                          │
     [Processing                      ▼
      Complete]              [DeadLetterQueue]
                                     │
                                     ▼
                         [CloudWatch Alarm]
                                     │
                                     ▼
                            [SNS Topic]
                                     │
                                     ▼
                         [MessageRedriver Lambda]
                                     │
                                     └─────────┐
                                              ▼
                                         [MainQueue]
```

Component Interactions:
1. Messages arrive in MainQueue for processing
2. MessageProcessor Lambda processes messages with retry capability
3. Failed messages are moved to DLQ after 3 retry attempts
4. CloudWatch monitors DLQ message count
5. When DLQ threshold is reached, CloudWatch alarm triggers
6. SNS topic receives alarm and invokes MessageRedriver
7. MessageRedriver moves messages back to MainQueue for reprocessing
8. Process continues until messages are successfully processed

## Infrastructure

![Infrastructure diagram](./docs/infra.svg)
The infrastructure is defined using AWS SAM and includes:

Lambda Functions:
- MessageProcessorFunction: Processes messages from MainQueue
- MessageRedriveFunction: Handles DLQ message recovery

SQS Queues:
- MainQueue: Primary message processing queue
- DeadLetterQueue: Stores failed messages

Monitoring:
- DLQMessageAlarm: CloudWatch alarm monitoring DLQ
- LogGroupMessageProcessor: CloudWatch logs for processor
- LogGroupMessageRedrive: CloudWatch logs for redriver

Messaging:
- DLQAlarmSNSTopic: SNS topic for alarm notifications
- DLQAlarmSNSTopicSubscription: Lambda subscription to SNS