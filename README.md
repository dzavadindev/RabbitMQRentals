# RabbitMQ Rentals

RabbitMQ Rentals is a small Java learning project for exploring message queues. It models room reservations across separate customer, rental-agent, and building processes that communicate through RabbitMQ instead of calling each other directly. It is a console demo.

## What the application does

- **Customer** presents a menu to list buildings, request rooms, confirm a reservation, or cancel one. Replies appear in the customer's terminal.
- **RentalAgent** receives customer requests, keeps a list of buildings that announce themselves, routes reservation requests to buildings, and forwards building replies back to customers.
- **Building** starts with 10 available rooms, announces itself periodically, creates reservation numbers, and tracks requested and confirmed reservations in memory. Confirming a request reduces its available-room count; canceling a confirmed reservation restores those rooms.

Messages contain JSON payloads and a `class` header identifying the request or reply. The customer's personal queue is supplied as the message's `replyTo` address. RabbitMQ exchanges and queues carry requests and replies between the three processes; buildings announce themselves via a fanout exchange every five seconds.

## Run locally

You will need a running RabbitMQ broker on the default local connection (`localhost:5672`, default `guest`/`guest` credentials), JDK 21, and IntelliJ IDEA. RabbitMQ Java client (`com.rabbitmq:amqp-client:5.19.0`) and Jackson Databind (`com.fasterxml.jackson.core:jackson-databind:2.15.0`) libraries are required.

**The project has no Maven or Gradle build file, I should get back to that**

1. Start RabbitMQ locally and open the project in IntelliJ IDEA. Ensure the project SDK and the two repository libraries are available.
2. Run `src.Building` in one terminal/run configuration. Its generated name appears as `Building<number>`.
3. Run `src.Customer` in a separate terminal. Leave its menu open for now.
4. Run `src.RentalAgent` in a third terminal. Starting the building and customer first matters: each declares an exchange that the agent binds to during startup.
5. Wait a few seconds for the agent to receive the building's announcement. In the customer menu, choose **Request all buildings**, then use the displayed building name with **Book a room**. Keep the reservation number printed in the customer terminal to confirm or cancel the request later.

Run additional `src.Building` or `src.Customer` instances in separate processes to experiment with multiple buildings or customers. Entry points generate names with random numbers, so names can occasionally collide. Reservations and room counts live only in each building process's memory; restarting it loses that state.

## Reservation notes

- A requested (unconfirmed) reservation and a confirmed reservation are canceled by the same building handler, but with different results: canceling an unconfirmed request removes it; canceling a confirmed one also returns its rooms to availability. The customer receives a different status message for each case.
- Only the customer instance that made a reservation can confirm or cancel it. The building checks the customer's reply queue name before taking either action.
- **Current routing limitation:** confirmation removes the building's binding for that reservation number. Because cancellation uses the same binding to reach the building, canceling a *confirmed* reservation through the customer menu may not be delivered, despite the building having logic to handle it.

## Console colors

- **Green:** a message was sent to another component.
- **Cyan:** a message was received and processed.
- **Red:** an error occurred.
- **Blue:** an unlikely duplicate reservation number was generated; the building tries creating the reservation again.
