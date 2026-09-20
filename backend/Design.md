### Order Table
I change the shipping_address to take a snapshot at the time of checkout.
User try to update their address book, historical orders must not change.

### Order Table shipping_address json filed why?
I store the raw JSON alongside, as an audit trail, so I can prove exactly what the user submitted and adapt to international format without a migration.
The flattened columns serve reporting, the Json serves audit and flexibilty. If the business asks for orders to Bangalore it is 
where shipping_city = 'Bangalore' on indexed column -fast, simple and the planner understand it.

### inventory reservation
To tackle the high concurrency scenario 530 user trying to grab 15 items at the exact same millisecond.
we reserved the stock for first 15 request.
The payment is successfull then decrement the stock otherwise payment fails
reservation (TTL) expires stock restored 
next request gets the item.

After 15 request we immediately return the 409/422.

