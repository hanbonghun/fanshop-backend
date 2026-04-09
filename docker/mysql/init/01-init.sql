CREATE DATABASE IF NOT EXISTS member_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS product_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS order_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON member_db.* TO 'fanshop'@'%';
GRANT ALL PRIVILEGES ON product_db.* TO 'fanshop'@'%';
GRANT ALL PRIVILEGES ON order_db.* TO 'fanshop'@'%';
FLUSH PRIVILEGES;
