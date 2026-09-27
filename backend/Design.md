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

After 15 request or stock reserved we immediately return the 409/422.


###  Imagine it’s Black Friday, and you have a highly anticipated product (e.g., a new PlayStation or limited-edition sneaker). You have 10,000 users clicking "Checkout" at the exact same millisecond. All 10,000 threads hit reserveInventory for the exact same product_id.Because of the row-level lock, Postgres will serialize these 10,000 requests. The database connection pool will exhaust, latency will spike to seconds, and the system will likely time out and crash. This is known as the "Hot Row" problem.
### Challenge: How do high-scale e-commerce companies (like Shopify or Amazon) handle inventory deduction for massively popular items without bringing down the database? (Hint: Think about inventory sharding, batching, or using an in-memory datastore for the "fast path").

To handle the 10,000 concurrent user request in same time
I using the redis for instant in-memory lookup completely decouples high-throughput checkout traffic frow row-level database locking.

If 10,000 requests hit Redis at once, standard GET followed by DECR operations will create race conditions. Instead, high-scale systems execute an atomic Lua script inside Redis.
Once Redis validates and reserves the stock on the fast path, I must reflect this state in PostgreSQL without hammering the hot row synchronously.




### InventoryRepositoryJDBC
I using the hardcore value to in sql query but in future update give typos error, using enum provide a single source of truth across the entire stack. 

### Imagine this scenario: The user clicks "Pay", the payment gateway processes it, but the network times out before the gateway receives the success response. The gateway retries the webhook. Your application receives the "Create Reservation" request twice for the same order and product.
### The first call succeeds. The second call hits the UNIQUE constraint and throws a DataIntegrityViolationException (or DuplicateKeyException).
### Question: How should your Service Layer handle this exception?
### If it crashes and returns a 500 error to the payment gateway, the gateway will keep retrying, and you'll pollute your logs. Should it catch the exception, query the database for the existing reservation, and return the existing ID? Defend your strategy for making insertReservation idempotent.

When the second duplicate webhook or checkout requests arrives, reserveInventory and insertReservation execute inside a transaction. 
Step 2 hits the postgres unique constraint on idempotent field and throws a spring DuplicateKeyException.


### released question what if the webhook received later after the ttl event already happend
The Race Condition Win: If the TTL job releases the reservation a millisecond before the payment webhook arrives, the first UPDATE affects 0 rows. The CTE returns empty. The second UPDATE matches nothing. jdbc.update() returns 0. Your service layer sees 0, knows the TTL won the race, and triggers the auto-refund. This is exactly how production money-path code is written.

### Using JDBC in inventory_reservation and JPA in other
Inventory has extreme write contention(10000 user trying to buy last product at the exact same millisecond.)
JPA optimistic locking would cause massive retry storm in java exhausting CPU.
JPA cannot easily express atomic, multi-table conditional updates, that why where we need absolute control over sql execution plan and row level locking, we use raw jdbc.

Cart have a low contention and a high read-to-write ratio.
It saves you from writing tedious SQL for simple CRUD operations.
