# Postman Collection

Import `statement-service.postman_collection.json` into Postman.

Default collection variables target `http://localhost:8080`.

For local/dev profile:

1. Run the app with `SPRING_PROFILES_ACTIVE=local` or `dev`.
2. Run the requests in `01 Local Dev Tokens` to populate `adminToken`, `customerToken`, and `otherCustomerToken`.

For docker/staging/production-like profile:

1. Do not use the dev-token requests; they should be unavailable.
2. Paste real JWTs into the collection variables `adminToken`, `customerToken`, and `otherCustomerToken`.
3. Make sure `customerToken` has `customer_id={{customerId}}` and `otherCustomerToken` has `customer_id={{otherCustomerId}}`.

For upload requests, Postman may require allowing files from the repository folder. If a file field is blank after import, select:

- `postman/sample-statement.pdf` for valid PDF uploads.
- `postman/fake-statement.pdf` for fake PDF validation.
- `postman/sample.txt` for invalid content-type validation.

The rate-limit folder assumes `RATE_LIMIT_DOWNLOAD_LINK_LIMIT=10`. In Collection Runner, `Download Link Loop Until 429` repeats up to 12 attempts. If your environment uses a different limit, adjust the `rateLimitAttempt` loop or send the request manually until the expected `429` appears.
