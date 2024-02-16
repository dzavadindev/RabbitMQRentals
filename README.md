# DOCUMENTATION

### Notes

- Canceling a confirmed reservation and cancelling an unconfirmed reservation are two different things, handled by the same consume handler. The two scenarios also result in different responses and action taken on the buildings side.
- If you are not the customer who made the reservation, you cannot delete it or confirm it. The confirmation/deletion is only possible by the client who made the reservation

### Outputs 
Green — Sent a message to another 
Cyan — Received and processed a message
Red — An error has occurred 
Blue — a highly unlikely exception has occurred, re-calling the method 