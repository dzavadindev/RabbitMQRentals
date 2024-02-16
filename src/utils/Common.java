package src.utils;

public interface Common {

    // EXCHANGES

    String EXCHANGE_PING_ALIVE_BUILDINGS = "ExchangePingAliveBuildings";
    String EXCHANGE_CUSTOMER_REQUEST = "ExchangeCustomerRequest";
    String EXCHANGE_BUILDINGS_REPLIES = "ExchangeBuildingsReplies";
    String EXCHANGE_RENTAL_AGENTS_REQUESTS = "ExchangeRentalAgentsRequests";

    // QUEUES

    String QUEUE_CUSTOMER_REQUEST = "QueueCustomerRequest";
    String QUEUE_BUILDINGS_REPLIES = "QueueBuildingsReplies";

    // KEYS

    String KEY_CUSTOMER_REQUEST = "KeyCustomerRequest";
    String KEY_BUILDINGS_REPLIES = "KeyBuildingsReplies";

}
