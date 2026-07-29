# REST Contract: Cardholder Contact Management

**Service**: notification-service
**Base Path**: `/api/v1/cardholder-contacts`
**Authentication**: None (internal endpoint — access controlled at api-gateway layer)
**Content-Type**: `application/json`

---

## Endpoints

### POST /api/v1/cardholder-contacts

Register new contact preferences for a card token. If the card token already exists, the
existing record is overwritten (upsert semantics).

**Request Body**:
```json
{
  "cardToken": "vault:v1:tok_abc123",
  "maskedCardNumber": "****-****-****-1234",
  "email": "cardholder@example.com",
  "phoneNumber": "+14155551234",
  "transactionNotificationsEnabled": true
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `cardToken` | String | Yes | Non-empty, opaque token |
| `maskedCardNumber` | String | Yes | Non-empty, display-safe masked form |
| `email` | String | No | Valid email format if present |
| `phoneNumber` | String | No | E.164 format if present |
| `transactionNotificationsEnabled` | Boolean | No | Defaults to `true` if omitted |

At least one of `email` or `phoneNumber` must be provided.

**Response 201 Created**:
```json
{
  "cardToken": "vault:v1:tok_abc123",
  "maskedCardNumber": "****-****-****-1234",
  "email": "cardholder@example.com",
  "phoneNumber": "+14155551234",
  "transactionNotificationsEnabled": true,
  "registeredAt": "2026-06-12T14:30:00Z",
  "updatedAt": "2026-06-12T14:30:00Z"
}
```

**Response 400 Bad Request**: Missing required fields or validation failure.

---

### PUT /api/v1/cardholder-contacts/{cardToken}

Update contact preferences for an existing card token. Creates the record if it does not
exist (full upsert).

**Path Parameter**: `cardToken` — URL-encoded opaque card token

**Request Body**: Same schema as POST.

**Response 200 OK**: Same schema as POST 201 response.

**Response 400 Bad Request**: Validation failure.

---

### GET /api/v1/cardholder-contacts/{cardToken}

Retrieve current contact preferences for a card token.

**Path Parameter**: `cardToken` — URL-encoded opaque card token

**Response 200 OK**: Same schema as POST 201 response.

**Response 404 Not Found**: No preferences registered for this card token.
```json
{
  "error": "No contact preferences found for card token"
}
```

---

## Validation Rules

- `cardToken`: required, non-blank
- `maskedCardNumber`: required, non-blank, MUST NOT match a raw PAN pattern (13–19 consecutive digits) — rejected if it appears to be an unmasked card number
- `email`: optional; if present, must be valid RFC 5322 email format
- `phoneNumber`: optional; if present, must start with `+` followed by 7-15 digits
- At least one of `email` or `phoneNumber` must be non-null
