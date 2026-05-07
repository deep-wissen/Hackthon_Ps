# Hacksys Backend: Production-Grade Failure-Prone System

Welcome to the hackathon! This system is a realistic, "messy" backend designed to test your AI agent's ability to analyze logs, detect root causes, and automate self-healing.

## 🚀 Deployment Instructions

### Local Run
1. Ensure you have Java 17 and Maven installed.
2. Run: `./mvnw spring-boot:run`
3. The server will start at `http://localhost:8080`.

### Render Deployment
1. Push this code to a GitHub repository.
2. Connect the repository to [Render](https://render.com).
3. Render will automatically detect the `render.yaml` file and deploy the service.
   - **Build Command:** `./mvnw clean install -DskipTests`
   - **Start Command:** `java -jar target/backend-1.0.0.jar`

---

## 📡 API Endpoints

### 1. Order Service
- **`POST /order`**: Create a new order.
  ```bash
  curl -X POST http://localhost:8080/order \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "items": [
      {"productId": "PROD-001", "quantity": 2, "unitPrice": 79.99}
    ]
  }'
  ```
- **`GET /order/{id}`**: Get order details.
- **`POST /order/cancel`**: Cancel an order.

### 2. Payment Service
- **`POST /pay`**: Process payment.
  ```bash
  curl -X POST http://localhost:8080/pay \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "REPLACE_WITH_ORDER_ID",
    "userId": "user-123",
    "amount": 159.98
  }'
  ```
- **`POST /refund`**: Refund a payment.

### 3. Inventory Service
- **`GET /inventory`**: List all stock.
- **`POST /inventory/update`**: Admin stock update.
  ```bash
  curl -X POST http://localhost:8080/inventory/update \
  -H "Content-Type: application/json" \
  -d '{
    "productId": "PROD-001",
    "delta": 10,
    "updatedBy": "admin"
  }'
  ```

### 4. Logging & Monitoring
- **`GET /logs`**: Fetch structured JSON logs.
- **`GET /logs?trace_id=...`**: Filter logs by trace.
- **`GET /health`**: System health status.

---

## 🧠 System Philosophy

This system is designed to fail in non-obvious, interconnected ways:
- **Idempotency**: Retrying requests might lead to duplicate charges or deductions.
- **Race Conditions**: Concurrent requests might bypass stock checks.
- **Silent Failures**: Some services might swallow errors but return success upstream.
- **Trace Propagation**: Some logs might lose their `trace_id` in asynchronous paths.

Good luck debugging!
