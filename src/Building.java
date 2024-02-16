package src;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.*;

import java.io.IOException;

import src.messages.*;

import java.util.*;
import java.util.concurrent.TimeoutException;

import static src.utils.Common.*;
import static src.utils.ANSIColors.*;

public class Building {
    private Channel channel;
    private final ObjectMapper mapper = new ObjectMapper();
    // personal properties
    private String consumerName = "";
    private int availableRooms;
    private final Set<Reservation> requestedReservations, confirmedReservations;
    // Set, so duplicate reservation numbers are not possible,
    // I have overwritten hashCode() and equals() of Reservation class,
    // to be completely sure that a set won't add two of the same reservation
    // Comparison of equals() only takes resnum into account.

    public Building(int availableRooms) {
        this.availableRooms = availableRooms;
        this.requestedReservations = new HashSet<>();
        this.confirmedReservations = new HashSet<>();
    }

    public static void main(String[] args) {
        Random rand = new Random();
        int id = rand.nextInt(100);
        String name = "Building" + id;
        new Building(10).run(name);
    }

    public void run(String name) {
        try {
            consumerName = name;
            // communication setup
            ConnectionFactory factory = new ConnectionFactory();
            Connection connection = factory.newConnection();
            channel = connection.createChannel();
            channel.addReturnListener((undeliveredMessage) -> System.out.println("Message " + (new String(undeliveredMessage.getBody())) + " not delivered"));

            // Personal building queue
            channel.queueDeclare(consumerName, false, false, false, null);

            // Buildings ping alive
            channel.exchangeDeclare(EXCHANGE_PING_ALIVE_BUILDINGS, BuiltinExchangeType.FANOUT, false);

            // Building replies to rental agents
            channel.exchangeDeclare(EXCHANGE_BUILDINGS_REPLIES, BuiltinExchangeType.DIRECT, false);

            System.out.println("Welcome to " + consumerName + "!");

            pingAlive();
            buildingConsume();

            // this thing is a "shutdown hook". its being executed before System.exit() when terminating the process.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                            .headers(Map.of("class", "BuildingDeath"))
                            .build();
                    byte[] message = mapper.writeValueAsBytes(consumerName);
                    channel.basicPublish(EXCHANGE_PING_ALIVE_BUILDINGS, "", props, message);
                } catch (IOException e) {
                    System.err.println("Failed to execute shutdown hook");
                    System.exit(400);
                }
            }));

        } catch (IOException | TimeoutException err) {
            throw new RuntimeException(err);
        }
    }

    private void pingAlive() {
        Timer t = new Timer();
        t.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    ReplyBuildingPing replyBuildingPing = new ReplyBuildingPing(consumerName);
                    byte[] bytes = mapper.writeValueAsBytes(replyBuildingPing);

                    AMQP.BasicProperties pingAliveProps = new AMQP.BasicProperties.Builder()
                            .headers(Map.of("class", "ReplyBuildingPing"))
                            .build();

                    channel.basicPublish(EXCHANGE_PING_ALIVE_BUILDINGS, "", pingAliveProps, bytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, 0, 5000);
    }

    private void buildingConsume() throws IOException {
        channel.basicConsume(consumerName, false, getDeliveryCallback(), ignore -> {
        });
    }

    private DeliverCallback getDeliveryCallback() {
        return (consumerTag, delivery) -> {
            try {
                if (delivery.getProperties().getHeaders() != null) {
                    String clazz = delivery.getProperties().getHeaders().get("class").toString();
                    switch (clazz) {
                        case "RequestRoomReservation" -> makeReservation(consumerTag, delivery);
                        case "RequestConfirmReservation" -> confirmReservation(consumerTag, delivery);
                        case "RequestCancelReservation" -> cancelReservation(consumerTag, delivery);
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
                } catch (IOException e) {
                    System.err.println("Failed to acknowledge message: " + e.getMessage());
                }
            }
        };
    }

    private void makeReservation(String tag, Delivery delivery) throws IOException {
        coloredPrint(ANSI_CYAN, "Received a requests for room reservation. Current number of available rooms is " + availableRooms);

        RequestRoomReservation receivedMessage = mapper.readValue(delivery.getBody(), RequestRoomReservation.class);
        String customerName = delivery.getProperties().getReplyTo();

        if (receivedMessage.rooms() > availableRooms) {
            // send an error message, rental agent handles all error messages the same.
            sendErrorMessage(delivery, consumerName + " doesn't have " + receivedMessage.rooms() + " available rooms. Only " + availableRooms + " rooms are available");
            return;
        }

        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(Map.of("class", "ReplyRoomReservation"))
                .replyTo(delivery.getProperties().getReplyTo())
                .build();

        Random rand = new Random();
        String reservationNumber = String.format("%04d", rand.nextInt(10000));
        ReplyRoomReservation data = new ReplyRoomReservation(reservationNumber, receivedMessage.buildingId());
        byte[] deliverMessage = mapper.writeValueAsBytes(data);

        if (!requestedReservations.add(new Reservation(customerName, reservationNumber, receivedMessage.rooms()))) {
            coloredPrint(ANSI_BLUE, "A DUPLICATE RESERVATION NUMBER HAS BEEN CREATED, TRYING RESERVATION CREATION AGAIN");
            makeReservation(tag, delivery);
        }

        channel.queueBind(consumerName, EXCHANGE_RENTAL_AGENTS_REQUESTS, reservationNumber);
        channel.basicPublish(EXCHANGE_BUILDINGS_REPLIES, KEY_BUILDINGS_REPLIES, props, deliverMessage);
    }

    private void confirmReservation(String tag, Delivery delivery) throws IOException {
        RequestConfirmReservation receivedMessage = mapper.readValue(delivery.getBody(), RequestConfirmReservation.class);
        coloredPrint(ANSI_GREEN, "Received a confirmation of reservation. Reservation number " + receivedMessage.reservationNumber());

        Reservation reservation = requestedReservations.stream()
                .filter(el -> el.reservationNumber.equals(receivedMessage.reservationNumber()))
                .findFirst()
                .orElse(null);

        if (reservation == null) {
            // send an error message, rental agent handles all error messages the same.
            sendErrorMessage(delivery, "Your reservation is not present in the list of reservation list of building " + consumerName + ". THere is nothing to confirm. If you think that something is not right, contact our customer support.");
            return;
        }

        if (!reservation.customerName.equals(delivery.getProperties().getReplyTo())) {
            // only the customer that made the reservation can confirm it
            sendErrorMessage(delivery, "You were not the one who made this reservation, thus you cannot confirm it");
            return;
        }

        requestedReservations.remove(reservation);
        availableRooms -= reservation.numberOfRooms;
        confirmedReservations.add(reservation);

        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(Map.of("class", "ReplyConfirmReservation"))
                .replyTo(delivery.getProperties().getReplyTo()).build();

        channel.queueUnbind(consumerName, EXCHANGE_RENTAL_AGENTS_REQUESTS, receivedMessage.reservationNumber());
        byte[] deliverMessage = mapper.writeValueAsBytes(new ReplyConfirmReservation(reservation.reservationNumber, consumerName));

        channel.basicPublish(EXCHANGE_BUILDINGS_REPLIES, KEY_BUILDINGS_REPLIES, props, deliverMessage);
    }

    private void cancelReservation(String tag, Delivery delivery) throws IOException {
        RequestCancelReservation receivedMessage = mapper.readValue(delivery.getBody(), RequestCancelReservation.class);
        coloredPrint(ANSI_GREEN, "Received a requests for room cancellation. Reservation number " + receivedMessage.reservationNumber());

        Reservation unconfirmedReservation = requestedReservations.stream()
                .filter(el -> el.reservationNumber.equals(receivedMessage.reservationNumber()))
                .findFirst()
                .orElse(null);

        Reservation confirmedReservation = confirmedReservations.stream()
                .filter(el -> el.reservationNumber.equals(receivedMessage.reservationNumber()))
                .findFirst()
                .orElse(null);

        // Cancel reservation exists
        if (confirmedReservation == null && unconfirmedReservation == null) {
            sendErrorMessage(delivery, "Reservation you are trying to cancel was not found. Check your reservation number for validity");
            return;
        }

        // Checks below confirm that its not another user trying to cancel another's reservation
        if (confirmedReservation != null && !confirmedReservation.customerName.equals(delivery.getProperties().getReplyTo())) {
            sendErrorMessage(delivery, "You were not the one who made this reservation, thus you cannot cancel it");
            return;
        }
        if (unconfirmedReservation != null && !unconfirmedReservation.customerName.equals(delivery.getProperties().getReplyTo())) {
            sendErrorMessage(delivery, "You were not the one who made this reservation, thus you cannot cancel it");
            return;
        }

        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(Map.of("class", "ReplyCancelReservation"))
                .replyTo(delivery.getProperties().getReplyTo()).build();

        byte[] deliverMessage = new byte[0];

        // Determine is it confirmed on an unconfirmed reservation being canceled
        if (unconfirmedReservation != null) {
            requestedReservations.remove(unconfirmedReservation);
            deliverMessage = mapper.writeValueAsBytes(new ReplyCancelReservation(unconfirmedReservation.reservationNumber, " An unconfirmed reservation has been successfully canceled"));
        }
        if (confirmedReservation != null) {
            availableRooms += confirmedReservation.numberOfRooms;
            confirmedReservations.remove(confirmedReservation);
            deliverMessage = mapper.writeValueAsBytes(new ReplyCancelReservation(confirmedReservation.reservationNumber, " A confirmed reservation has been successfully canceled." + availableRooms + " are now available at " + consumerName));
        }

        channel.queueUnbind(consumerName, EXCHANGE_RENTAL_AGENTS_REQUESTS, receivedMessage.reservationNumber());
        channel.basicPublish(EXCHANGE_BUILDINGS_REPLIES, KEY_BUILDINGS_REPLIES, props, deliverMessage);
    }

    private void sendErrorMessage(Delivery delivery, String message) throws IOException {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(Map.of("class", "ReplyExecutionError"))
                .replyTo(delivery.getProperties().getReplyTo()).build();

        byte[] deliverMessage = mapper.writeValueAsBytes(new ReplyExecutionError(message));

        channel.basicPublish(EXCHANGE_BUILDINGS_REPLIES, KEY_BUILDINGS_REPLIES, props, deliverMessage);
    }

    record Reservation(String customerName, String reservationNumber, int numberOfRooms) {
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Reservation)) return false;
            return ((Reservation) obj).reservationNumber.equals(this.reservationNumber);
        }

        @Override
        public int hashCode() {
            int multiplier = 31;
            int result = 1;
            result = result * multiplier + this.reservationNumber.hashCode();
            result = result * multiplier + this.customerName.hashCode();
            return result;
        }
    }
}