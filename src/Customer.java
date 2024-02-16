package src;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import src.messages.*;
import src.utils.Menu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeoutException;

import static src.utils.Common.*;
import static src.utils.ANSIColors.*;


public class Customer {
    private ConnectionFactory factory;
    private Connection connection;
    private Channel channel;
    private ObjectMapper mapper = new ObjectMapper();
    private String consumerName;


    public static void main(String[] args) {
        Random rand = new Random();
        int id = rand.nextInt(100);
        String name = "Customer" + id;
        new Customer().run(name);
    }

    public void run(String name) {
        try {
            consumerName = name;
            factory = new ConnectionFactory();
            connection = factory.newConnection();
            channel = connection.createChannel();
            channel.addReturnListener((undeliveredMessage) ->
                    System.out.println("Message " + (new String(undeliveredMessage.getBody())) + " not delivered")
            );

            // PERSONAL CUSTOMER QUEUE
            channel.queueDeclare(consumerName, false, false, false, null);

            // CUSTOMER REQUEST
            channel.exchangeDeclare(EXCHANGE_CUSTOMER_REQUEST, BuiltinExchangeType.DIRECT);

            customerConsume();
            printCustomerMenu();

        } catch (IOException | TimeoutException | IllegalStateException e) {
            throw new RuntimeException(e);
        }
    }

    private void customerConsume() throws IOException {
        channel.basicConsume(consumerName, false, getDeliveryCallback(), ignore -> {
        });
    }

    private DeliverCallback getDeliveryCallback() {
        return (consumerTag, delivery) -> {
            try {
                // is null when no headers are provided, need to check for
                // it not to be null because it crashes the whole thing.
                if (delivery.getProperties().getHeaders() != null) {
                    String clazz = delivery.getProperties().getHeaders().get("class").toString();
                    switch (clazz) {
                        case "ReplyRoomReservation" -> receiveMakeReservation(consumerTag, delivery);
                        case "ReplyConfirmReservation" -> confirmReservation(consumerTag, delivery);
                        case "ReplyCancelReservation" -> cancelReservation(consumerTag, delivery);
                        case "ReplyBuildingsList" -> receiveBuildingsList(consumerTag, delivery);
                        case "ReplyExecutionError" -> receiveErrorMessage(consumerTag, delivery);
                        default -> throw new IllegalArgumentException("Unknown class header " + clazz);
                    }
                } else {
                    System.out.println("Received a message without header, further action unknown");
                }
            } catch (Exception err) {
                System.err.println("An error occurred before acknowledging: " + err.fillInStackTrace());
            } finally {
                try {
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), true);
                    printCustomerMenu();
                } catch (IOException e) {
                    System.err.println("Failed to acknowledge message: " + e.getMessage());
                }
            }
        };
    }

    private void receiveErrorMessage(String consumerTag, Delivery delivery) throws IOException {
        ReplyExecutionError message = mapper.readValue(delivery.getBody(), ReplyExecutionError.class);

        coloredPrint(ANSI_RED, "An error has occurred: " + message.errorMessage());
    }

    private void cancelReservation(String consumerTag, Delivery delivery) throws IOException {
        ReplyCancelReservation message = mapper.readValue(delivery.getBody(), ReplyCancelReservation.class);

        coloredPrint(ANSI_CYAN, message.status());
    }

    private void confirmReservation(String consumerTag, Delivery delivery) throws IOException {
        ReplyConfirmReservation message = mapper.readValue(delivery.getBody(), ReplyConfirmReservation.class);

        coloredPrint(ANSI_CYAN, "Thank you for confirming your reservation at " +
                message.buildingId() +
                ". See you soon!");
    }

    private void receiveMakeReservation(String consumerTag, Delivery delivery) throws IOException {
        ReplyRoomReservation message = mapper.readValue(delivery.getBody(), ReplyRoomReservation.class);

        coloredPrint(ANSI_CYAN, "Thank you for making a reservation at " +
                message.buildingId() +
                ". Your reservation number is " +
                message.reservationNumber());
    }

    private void receiveBuildingsList(String tag, Delivery delivery) {
        String buildingsList = new String(delivery.getBody(), StandardCharsets.UTF_8);
        coloredPrint(ANSI_CYAN, "Received buildings list: " + buildingsList);
    }

    private void requestBuildingsList() throws IOException {
        AMQP.BasicProperties props = new AMQP.BasicProperties
                .Builder()
                .headers(Map.of("class", "RequestBuildingsList"))
                .replyTo(consumerName)
                .build();

        byte[] message = mapper.writeValueAsBytes(consumerName);
        channel.basicPublish(EXCHANGE_CUSTOMER_REQUEST, KEY_CUSTOMER_REQUEST, props, message);

        coloredPrint(ANSI_GREEN, "Sent a request for buildings list");
    }

    private void requestRoomReservation(int numberOfRooms, String buildingId) throws IOException {
        AMQP.BasicProperties props = new AMQP.BasicProperties
                .Builder()
                .headers(Map.of("class", "RequestRoomReservation"))
                .replyTo(consumerName)
                .build();

        RequestRoomReservation request = new RequestRoomReservation(numberOfRooms, buildingId);
        byte[] message = mapper.writeValueAsBytes(request);

        channel.basicPublish(EXCHANGE_CUSTOMER_REQUEST, KEY_CUSTOMER_REQUEST, props, message);

        coloredPrint(ANSI_GREEN, "Sent a request for reservation in building " + buildingId);
    }

    private void requestReservationConfirmation(String reservationNumber) throws IOException {
        AMQP.BasicProperties props = new AMQP.BasicProperties
                .Builder()
                .headers(Map.of("class", "RequestConfirmReservation"))
                .replyTo(consumerName)
                .build();

        RequestConfirmReservation request = new RequestConfirmReservation(reservationNumber);
        byte[] message = mapper.writeValueAsBytes(request);

        channel.basicPublish(EXCHANGE_CUSTOMER_REQUEST, KEY_CUSTOMER_REQUEST, props, message);

        coloredPrint(ANSI_GREEN, "Sent a confirmation for reservation " + reservationNumber);
    }

    private void requestReservationCancellation(String reservationNumber) throws IOException {
        AMQP.BasicProperties props = new AMQP.BasicProperties
                .Builder()
                .headers(Map.of("class", "RequestCancelReservation"))
                .replyTo(consumerName)
                .build();

        RequestCancelReservation request = new RequestCancelReservation(reservationNumber);
        byte[] message = mapper.writeValueAsBytes(request);

        channel.basicPublish(EXCHANGE_CUSTOMER_REQUEST, KEY_CUSTOMER_REQUEST, props, message);

        coloredPrint(ANSI_GREEN, "Sent a cancellation request of reservation " + reservationNumber);
    }

    private void printCustomerMenu() throws IOException {
        Scanner sc = new Scanner(System.in);
        int chosenOption = Menu.printMenu("Request all buildings", "Book a room", "Confirm reservation", "Cancel a reservation");
        switch (chosenOption) {
            case 1 -> requestBuildingsList();
            case 2 -> {
                System.out.println("Enter the building where you would like to rent in (As shown when viewing buildings list):");
                String id = sc.next();
                int numOfRooms;
                while (true) {
                    System.out.println("Please enter a number: ");
                    try {
                        numOfRooms = sc.nextInt();
                        break;
                    } catch (InputMismatchException e) {
                        System.out.println("That's not a valid number. Please try again.");
                        sc.next();
                    }
                }
                requestRoomReservation(numOfRooms, id);
            }
            case 3 -> {
                System.out.println("Enter your reservation number to confirm your reservation: ");
                String resNum = sc.next();
                requestReservationConfirmation(resNum);
            }
            case 4 -> {
                System.out.println("Enter your reservation number to cancel your reservation: ");
                String resNum = sc.next();
                requestReservationCancellation(resNum);
            }
            case 0 -> {
                coloredPrint(ANSI_YELLOW, "Thank you for using our services!");
                System.exit(200);
            }
            default -> printCustomerMenu();
        }
    }

}
