CREATE TABLE IF NOT EXISTS product (
  sku VARCHAR(40) PRIMARY KEY, name VARCHAR(100) NOT NULL,
  price_minor BIGINT NOT NULL CHECK(price_minor > 0), currency VARCHAR(3) NOT NULL,
  available_stock INT NOT NULL CHECK(available_stock >= 0)
);
CREATE TABLE IF NOT EXISTS orders (
  id VARCHAR(36) PRIMARY KEY, idempotency_key VARCHAR(120) NOT NULL UNIQUE,
  request_hash VARCHAR(64) NOT NULL, customer_ref VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL, currency VARCHAR(3) NOT NULL,
  total_minor BIGINT NOT NULL CHECK(total_minor > 0), scenario VARCHAR(30) NOT NULL,
  created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, payment_id VARCHAR(36)
);
CREATE TABLE IF NOT EXISTS order_line (
  order_id VARCHAR(36) NOT NULL REFERENCES orders(id), sku VARCHAR(40) NOT NULL REFERENCES product(sku),
  quantity INT NOT NULL CHECK(quantity > 0), unit_price_minor BIGINT NOT NULL,
  line_total_minor BIGINT NOT NULL, PRIMARY KEY(order_id,sku)
);
CREATE TABLE IF NOT EXISTS payment_command (
  id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL UNIQUE REFERENCES orders(id),
  status VARCHAR(20) NOT NULL, attempts INT NOT NULL DEFAULT 0,
  next_attempt_at BIGINT NOT NULL, lease_until BIGINT, lease_token VARCHAR(36),
  last_error VARCHAR(500) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_command_due ON payment_command(status,next_attempt_at,lease_until);
INSERT INTO product(sku,name,price_minor,currency,available_stock)
 SELECT 'BOOK','Demo engineering book',2500,'USD',100 WHERE NOT EXISTS(SELECT 1 FROM product WHERE sku='BOOK');
INSERT INTO product(sku,name,price_minor,currency,available_stock)
 SELECT 'KEYBOARD','Demo keyboard',6000,'USD',20 WHERE NOT EXISTS(SELECT 1 FROM product WHERE sku='KEYBOARD');
INSERT INTO product(sku,name,price_minor,currency,available_stock)
 SELECT 'HEADSET','Demo headset',9000,'USD',10 WHERE NOT EXISTS(SELECT 1 FROM product WHERE sku='HEADSET');
