package src;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import src.messages.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeoutException;

import static src.utils.ANSIColors.*;
import static src.utils.Common.*;

public class RentalAgent {

    private final LinkedList<String> availableBuildingIds = new LinkedList<>();
    private ConnectionFactory factory;
    private Connection connection;
    private Channel channel;
    private final ObjectMapper mapper = new ObjectMapper();
    private String consumerName = "";

    public static void main(String[] args) {
        Random rand = new Random();
        int id = rand.nextInt(100);
        String name = "RentalAgent" + id;
        new RentalAgent().run(name);
    }

    public void run(String name) {
        try {
            consumerName = name;

            factory = new ConnectionFactory();
            connection = factory.newConnection();
            channel = connection.createChannel();
            channel.addReturnListener((undeliveredMessage) -> {
                try {
                    coloredPrint(ANSI_RED, "Message " + (new String(undeliveredMessage.getBody())) + " not delivered to building. Redirecting error message to the customer");
                    String replyTo = undeliveredMessage.getProperties().getReplyTo();
                    AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                            .headers(Map.of("class", "ReplyExecutionError"))
                            .build();
                    byte[] error = mapper.writeValueAsBytes(new ReplyExecutionError("Your message was not delivered to the building. Try checking validity of your reservation number or the spelling of the buildings name for typos"));
                    channel.basicPublish("", replyTo, true, props, error);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            // PERSONAL QUEUE
            channel.queueDeclare(consumerName, false, false, false, null);

            // BUILDING PING ALIVE RECEIVING
            String buildingStatusUpdateQueue = consumerName + "UpdateQueue";
            channel.queueDeclare(buildingStatusUpdateQueue, false, false, true, null);
            channel.queueBind(buildingStatusUpdateQueue, EXCHANGE_PING_ALIVE_BUILDINGS, "");

            // CUSTOMER REQUEST RECEIVING
            channel.queueDeclare(QUEUE_CUSTOMER_REQUEST, false, false, false, null);
            channel.queueBind(QUEUE_CUSTOMER_REQUEST, EXCHANGE_CUSTOMER_REQUEST, KEY_CUSTOMER_REQUEST);

            // RECEIVING REPLIES FROM BUILDINGS
            channel.queueDeclare(QUEUE_BUILDINGS_REPLIES, false, false, false, null);
            channel.queueBind(QUEUE_BUILDINGS_REPLIES, EXCHANGE_BUILDINGS_REPLIES, KEY_BUILDINGS_REPLIES);

            // SENDING REQUEST TO BUILDINGS
            channel.exchangeDeclare(EXCHANGE_RENTAL_AGENTS_REQUESTS, BuiltinExchangeType.DIRECT);

            rentalAgentConsume(buildingStatusUpdateQueue);

        } catch (IOException | TimeoutException err) {
            throw new RuntimeException(err);
        }
    }

    private void rentalAgentConsume(String buildingStatusUpdateQueue) throws IOException {
        // Set up consumer for customer requests
        channel.basicConsume(QUEUE_CUSTOMER_REQUEST, false, getDeliverCallback(), consumerTag -> {
        });

        // Set up consumer for buildings replies like successful/unsuccessful reservation/confirmation/cancellation
        channel.basicConsume(QUEUE_BUILDINGS_REPLIES, false, getDeliverCallback(), consumerTag -> {
        });

        // Set up consumer from the personal queue
        channel.basicConsume(consumerName, false, getDeliverCallback(), consumerTag -> {
        });

        // Set up consumer for ping alive of buildings
        channel.basicConsume(buildingStatusUpdateQueue, false, getDeliverCallback(), consumerTag -> {
        });
    }

    private DeliverCallback getDeliverCallback() {
        return (consumerTag, delivery) -> {
            try {
                if (delivery.getProperties().getHeaders() != null) {
                    String clazz = delivery.getProperties().getHeaders().get("class").toString();
                    switch (clazz) {
                        // ping alive
                        case "ReplyBuildingPing" -> updateBuildingsList(consumerTag, delivery);
                        case "BuildingDeath" -> removeBuildingFromList(consumerTag, delivery);
                        // buildings list
                        case "RequestBuildingsList" -> replyBuildingsList(consumerTag, delivery);
                        // reservation of a room
                        case "RequestRoomReservation" -> executeMakingReservation(consumerTag, delivery);
                        case "ReplyRoomReservation" -> replyMakingReservation(consumerTag, delivery);
                        // confirmation of a reservation
                        case "RequestConfirmReservation" -> executeConfirmReservation(consumerTag, delivery);
                        case "ReplyConfirmReservation" -> replyConfirmReservation(consumerTag, delivery);
                        // cancellation of a reservation
                        case "RequestCancelReservation" -> executeCancelReservation(consumerTag, delivery);
                        case "ReplyCancelReservation" -> replyCancelReservation(consumerTag, delivery);
                        // error handling
                        case "ReplyExecutionError" -> handleError(consumerTag, delivery);
                        default -> throw new IllegalArgumentException("Unknown class header " + clazz);
                    }
                } else {
                    coloredPrint(ANSI_YELLOW, "Received a message without header, further action unknown");
                }
            } catch (Exception err) {
                System.err.println("An error occurred before acknowledging: " + err.fillInStackTrace());
            } finally {
                try {
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), true);
                } catch (IOException e) {
                    System.err.println("Failed to acknowledge message: " + e.getMessage());
                }
            }
        };
    }

    private void handleError(String consumerTag, Delivery delivery) throws IOException {
        String replyTo = delivery.getProperties().getReplyTo();
        channel.basicPublish("", replyTo, true, delivery.getProperties(), delivery.getBody());
        coloredPrint(ANSI_RED, "Received an error from building. Redirecting the issue to " + replyTo);
    }

    private void replyCancelReservation(String consumerTag, Delivery delivery) throws IOException {
        String replyTo = delivery.getProperties().getReplyTo();
        ReplyCancelReservation message = mapper.readValue(delivery.getBody(), ReplyCancelReservation.class);

        // Send the reply to the reply-to address specified by the message
        channel.basicPublish("", replyTo, true, delivery.getProperties(), delivery.getBody());
        coloredPrint(ANSI_GREEN, " Reservation " + message.reservationNumber() + " canceled");
    }

    private void replyConfirmReservation(String consumerTag, Delivery delivery) throws IOException {
        String replyTo = delivery.getProperties().getReplyTo();
        ReplyConfirmReservation message = mapper.readValue(delivery.getBody(), ReplyConfirmReservation.class);

        // Send the reply to the reply-to address specified by the message
        channel.basicPublish("", replyTo, true, delivery.getProperties(), delivery.getBody());
        coloredPrint(ANSI_GREEN, "Reservation " +
                message.reservationNumber() +
                " of " +
                replyTo +
                " in " +
                message.buildingId() +
                " successfully confirmed");
    }

    private void replyMakingReservation(String consumerTag, Delivery delivery) throws IOException {
        String replyTo = delivery.getProperties().getReplyTo();

        // Send the reply to the reply-to address specified by the message
        channel.basicPublish("", replyTo, true, delivery.getProperties(), delivery.getBody());
        coloredPrint(ANSI_GREEN, "Replied with reservation number to " + replyTo);
    }

    private void replyBuildingsList(String tag, Delivery delivery) throws IOException {
        System.out.println("Collecting buildings list...");
        String replyTo = delivery.getProperties().getReplyTo();

        // Prepare the list of available building IDs
        String jsonString = mapper.writeValueAsString(availableBuildingIds);
        byte[] buildingsListBytes = jsonString.getBytes(StandardCharsets.UTF_8);

        // Prepare the properties for the reply
        AMQP.BasicProperties replyProps = new AMQP.BasicProperties.Builder()
                .headers(Map.of("class", "ReplyBuildingsList"))
                .build();

        // Send the reply to the reply-to address specified by the message
        channel.basicPublish("", replyTo, true, replyProps, buildingsListBytes);
        coloredPrint(ANSI_GREEN, "Replied with buildings list to " + replyTo);
    }


    private void updateBuildingsList(String tag, Delivery delivery) throws IOException {
        // debyteify the buildings list
        byte[] byteArray = delivery.getBody();
        ReplyBuildingPing building = mapper.readValue(byteArray, ReplyBuildingPing.class);
        // use synchronized to keep buildings list thread safe
        synchronized (availableBuildingIds) {
            // only add the building that pinged to the list is its not already in there
            if (!availableBuildingIds.contains(building.nameOfBuilding())) {
                availableBuildingIds.add(building.nameOfBuilding());
                coloredPrint(ANSI_CYAN, "Updated building list with ID: " + building.nameOfBuilding());
            }
        }
    }

    private void removeBuildingFromList(String consumerTag, Delivery delivery) throws IOException {
        String buildingId = mapper.readValue(delivery.getBody(), String.class);
        availableBuildingIds.remove(buildingId);
        channel.queueDelete(buildingId);
        coloredPrint(ANSI_CYAN, buildingId + " has been shut off, removing from active list.");
    }

    private void executeMakingReservation(String consumerTag, Delivery delivery) throws IOException {
        String toBuilding = mapper.readValue(delivery.getBody(), RequestRoomReservation.class).buildingId();

        channel.basicPublish("", toBuilding, true, delivery.getProperties(), delivery.getBody());

        coloredPrint(ANSI_GREEN, "Sent a request to building " + toBuilding);
    }

    private void executeConfirmReservation(String consumerTag, Delivery delivery) throws IOException {
        String reservationNumber = mapper.readValue(delivery.getBody(), RequestConfirmReservation.class).reservationNumber();

        channel.basicPublish(EXCHANGE_RENTAL_AGENTS_REQUESTS, reservationNumber, true, delivery.getProperties(), delivery.getBody());

        coloredPrint(ANSI_GREEN, "Sent a confirmation request to building with reservation " + reservationNumber);
    }

    private void executeCancelReservation(String consumerTag, Delivery delivery) throws IOException {
        String reservationNumber = mapper.readValue(delivery.getBody(), RequestCancelReservation.class).reservationNumber();

        channel.basicPublish(EXCHANGE_RENTAL_AGENTS_REQUESTS, reservationNumber, true, delivery.getProperties(), delivery.getBody());

        coloredPrint(ANSI_GREEN, "Sent a cancellation request to building with reservation " + reservationNumber);
    }
}