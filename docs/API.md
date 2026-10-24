# API reference

Full OpenAPI 3.0 specs:

- [docs/openapi/cart-service.openapi.yaml](openapi/cart-service.openapi.yaml): Service A, Add to Cart
- [docs/openapi/checkout-service.openapi.yaml](openapi/checkout-service.openapi.yaml): Service B, Checkout

Errors use RFC 9457 problem details (`application/problem+json`).

## Cart service (Service A)

Unauthenticated, for driving the demo. Carts live in memory and are created on first use.

| Method | Path | Purpose | Success |
|---|---|---|---|
| GET | `/api/carts/{cartId}` | Read a cart | 200 |
| POST | `/api/carts/{cartId}/items` | Add an item | 201 |
| POST | `/api/carts/{cartId}/checkout` | Send the cart to checkout-service | 201 |

Add an item:

```json
{ "sku": "book-001", "quantity": 2, "unitPriceCents": 1250 }
```

Checkout returns the order from checkout-service and clears the cart. Empty cart: 409. Checkout rejected: 502. No JWT-SVID from SPIRE: 503.

## Checkout service (Service B)

Every `/api/**` request requires `Authorization: Bearer <JWT-SVID>`.

| Method | Path | Purpose | Success |
|---|---|---|---|
| POST | `/api/v1/orders` | Place an order | 201 |
| GET | `/api/v1/orders/{orderId}` | Read an order | 200 |
| GET | `/actuator/health` | Health (no auth) | 200 |

Place an order:

```json
{
  "cartId": "demo-cart",
  "items": [ { "sku": "book-001", "quantity": 2, "unitPriceCents": 1250 } ]
}
```

Response:

```json
{
  "orderId": "ord_3f2c9d1e-7a5b-4c1e-9d8f-2b6a1c0e4f77",
  "cartId": "demo-cart",
  "totalCents": 2500,
  "status": "PLACED",
  "placedBy": "spiffe://example.org/ns/cart/sa/cart-service",
  "createdAt": "2026-10-24T10:00:00Z"
}
```

`placedBy` is the SPIFFE ID the service authenticated, not something the client sent.

### Authentication and authorization

| Case | Status |
|---|---|
| No `Authorization` header, or not `Bearer` | 401 |
| Token is malformed, unsigned, forged, expired, or has the wrong audience | 401 |
| Token is valid, but its SPIFFE ID is not in `SPIFFE_ALLOWED_CALLERS` | 403 |
| Token is valid and the SPIFFE ID is allowed | Request proceeds |

Checks are made against the SPIRE trust bundle for `example.org`, using the Workload API.

## Identities

| Workload | Kubernetes SA | SPIFFE ID |
|---|---|---|
| Cart service (A) | `cart/cart-service` | `spiffe://example.org/ns/cart/sa/cart-service` |
| Checkout service (B) | `checkout/checkout-service` | `spiffe://example.org/ns/checkout/sa/checkout-service` |
