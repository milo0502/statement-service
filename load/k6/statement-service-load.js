import http from 'k6/http';
import { check, fail, sleep } from 'k6';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const CUSTOMER_ID = __ENV.CUSTOMER_ID || `cust-load-${RUN_ID}`;
const LARGE_UPLOAD_BYTES = intEnv('LARGE_UPLOAD_BYTES', 9 * 1024 * 1024);
const UNIQUE_UPLOAD_BYTES = intEnv('UNIQUE_UPLOAD_BYTES', 64 * 1024);
const DUPLICATE_UPLOAD_BYTES = intEnv('DUPLICATE_UPLOAD_BYTES', 256 * 1024);

export const options = {
  discardResponseBodies: false,
  thresholds: {
    checks: ['rate>0.95'],
    http_req_duration: ['p(95)<1000', 'p(99)<2500'],
  },
  scenarios: {
    large_uploads: {
      executor: 'constant-vus',
      vus: intEnv('LARGE_UPLOAD_VUS', 1),
      duration: __ENV.LARGE_UPLOAD_DURATION || '30s',
      exec: 'largeUploads',
      startTime: '0s',
    },
    duplicate_uploads: {
      executor: 'constant-vus',
      vus: intEnv('DUPLICATE_UPLOAD_VUS', 5),
      duration: __ENV.DUPLICATE_UPLOAD_DURATION || '30s',
      exec: 'duplicateUploads',
      startTime: '35s',
    },
    download_link_abuse: {
      executor: 'constant-arrival-rate',
      rate: intEnv('DOWNLOAD_LINK_RATE', 20),
      timeUnit: '1s',
      duration: __ENV.DOWNLOAD_LINK_DURATION || '30s',
      preAllocatedVUs: intEnv('DOWNLOAD_LINK_PREALLOCATED_VUS', 20),
      maxVUs: intEnv('DOWNLOAD_LINK_MAX_VUS', 100),
      exec: 'downloadLinkAbuse',
      startTime: '70s',
    },
    many_unique_uploads: {
      executor: 'constant-vus',
      vus: intEnv('UNIQUE_UPLOAD_VUS', 5),
      duration: __ENV.UNIQUE_UPLOAD_DURATION || '30s',
      exec: 'manyUniqueUploads',
      startTime: '105s',
    },
    pool_pressure: {
      executor: 'constant-vus',
      vus: intEnv('POOL_PRESSURE_VUS', 10),
      duration: __ENV.POOL_PRESSURE_DURATION || '30s',
      exec: 'poolPressure',
      startTime: '140s',
    },
  },
};

export function setup() {
  const adminToken = token('ADMIN_TOKEN', 'admin', 'admin');
  const customerToken = token('CUSTOMER_TOKEN', CUSTOMER_ID, 'customer');
  const seedStatementId = createStatement(
    adminToken,
    CUSTOMER_ID,
    `acc-seed-${RUN_ID}`,
    '2026-01-01',
    '2026-01-31',
    makePdf(UNIQUE_UPLOAD_BYTES),
    `seed-${RUN_ID}.pdf`
  );

  return {
    adminToken,
    customerToken,
    seedStatementId,
    duplicateCustomerId: CUSTOMER_ID,
    duplicateAccountId: `acc-duplicate-${RUN_ID}`,
    duplicatePeriodStart: '2026-02-01',
    duplicatePeriodEnd: '2026-02-28',
  };
}

export function largeUploads(data) {
  const suffix = uniqueSuffix();
  const response = uploadStatement(
    data.adminToken,
    CUSTOMER_ID,
    `acc-large-${suffix}`,
    '2026-03-01',
    '2026-03-31',
    makePdf(LARGE_UPLOAD_BYTES),
    `large-${suffix}.pdf`
  );
  check(response, {
    'large upload created': (r) => r.status === 201,
  });
  sleep(1);
}

export function duplicateUploads(data) {
  const response = uploadStatement(
    data.adminToken,
    data.duplicateCustomerId,
    data.duplicateAccountId,
    data.duplicatePeriodStart,
    data.duplicatePeriodEnd,
    makePdf(DUPLICATE_UPLOAD_BYTES),
    `duplicate-${RUN_ID}.pdf`
  );
  check(response, {
    'duplicate upload returns created/idempotent response': (r) => r.status === 201,
  });
  sleep(0.2);
}

export function downloadLinkAbuse(data) {
  const response = http.post(
    `${BASE_URL}/api/v1/statements/${data.seedStatementId}/download-link`,
    JSON.stringify({ ttlSeconds: 300 }),
    {
      headers: authJsonHeaders(data.customerToken),
      responseCallback: http.expectedStatuses({ min: 200, max: 299 }, 429),
    }
  );
  check(response, {
    'download-link returns ok or rate limited': (r) => r.status === 200 || r.status === 429,
  });
}

export function manyUniqueUploads(data) {
  const suffix = uniqueSuffix();
  const customerId = `cust-unique-${RUN_ID}-${suffix}`;
  const response = uploadStatement(
    data.adminToken,
    customerId,
    `acc-unique-${suffix}`,
    '2026-04-01',
    '2026-04-30',
    makePdf(UNIQUE_UPLOAD_BYTES),
    `unique-${suffix}.pdf`
  );
  check(response, {
    'unique upload created': (r) => r.status === 201,
  });
  sleep(0.2);
}

export function poolPressure(data) {
  const response = http.get(`${BASE_URL}/api/v1/statements?page=0&size=100`, {
    headers: authHeaders(data.adminToken),
  });
  check(response, {
    'pool pressure list ok': (r) => r.status === 200,
  });
  sleep(0.1);
}

function createStatement(adminToken, customerId, accountId, periodStart, periodEnd, pdf, filename) {
  const response = uploadStatement(adminToken, customerId, accountId, periodStart, periodEnd, pdf, filename);
  if (response.status !== 201) {
    fail(`setup upload failed status=${response.status} body=${response.body}`);
  }
  const body = response.json();
  if (!body || !body.id) {
    fail(`setup upload did not return statement id body=${response.body}`);
  }
  return body.id;
}

function uploadStatement(adminToken, customerId, accountId, periodStart, periodEnd, pdf, filename) {
  return http.post(
    `${BASE_URL}/api/v1/statements`,
    {
      customerId,
      accountId,
      periodStart,
      periodEnd,
      file: http.file(pdf, filename, 'application/pdf'),
    },
    {
      headers: authHeaders(adminToken),
      timeout: __ENV.UPLOAD_TIMEOUT || '60s',
    }
  );
}

function token(envName, customerId, scope) {
  const fromEnv = __ENV[envName];
  if (fromEnv) {
    return fromEnv;
  }
  if (__ENV.DEV_TOKENS !== 'true') {
    fail(`${envName} is required unless DEV_TOKENS=true is set for a local/dev profile`);
  }

  const response = http.post(
    `${BASE_URL}/api/v1/dev/token`,
    JSON.stringify({ customerId, scope }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (response.status !== 200) {
    fail(`dev token request failed for ${envName} status=${response.status} body=${response.body}`);
  }
  return response.json('token');
}

function authHeaders(tokenValue) {
  return {
    Authorization: `Bearer ${tokenValue}`,
  };
}

function authJsonHeaders(tokenValue) {
  return {
    Authorization: `Bearer ${tokenValue}`,
    'Content-Type': 'application/json',
  };
}

function makePdf(sizeBytes) {
  const header = '%PDF-1.4\n1 0 obj\n<<>>\nendobj\n';
  const footer = '\ntrailer\n<<>>\n%%EOF\n';
  return header + '0'.repeat(Math.max(0, sizeBytes - header.length - footer.length)) + footer;
}

function uniqueSuffix() {
  return `${__VU}-${__ITER}-${Math.floor(Math.random() * 1_000_000)}`;
}

function intEnv(name, fallback) {
  const value = Number.parseInt(__ENV[name] || `${fallback}`, 10);
  return Number.isFinite(value) && value > 0 ? value : fallback;
}
