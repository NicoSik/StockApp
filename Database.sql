-- Database: Trading

-- DROP DATABASE IF EXISTS "Trading";
	
-- DROP TABLE IF EXISTS stock_price;
-- DROP TABLE IF EXISTS stock;
-- DROP TABLE IF EXISTS watchlist;
-- DROP TABLE IF EXISTS users;
CREATE TABLE IF NOT EXISTS stock(
    id SERIAL PRIMARY KEY,
    symbol TEXT NOT NULL UNIQUE,
    company TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS stock_price(
    id SERIAL PRIMARY KEY,
    stock_id INTEGER,
    date DATE NOT NULL,  -- 
    volume INT NOT NULL,
    FOREIGN KEY (stock_id) REFERENCES stock(id)
);

CREATE TABLE IF NOT EXISTS watchlist(
    id TEXT PRIMARY KEY,
    name TEXT
);

CREATE TABLE IF NOT EXISTS users(  
    id TEXT PRIMARY KEY,
    username TEXT NOT NULL
);
	
	
	