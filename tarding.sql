-- Database: Trading

-- DROP DATABASE IF EXISTS "Trading";

CREATE DATABASE "Trading"
    WITH
    OWNER = postgres
    ENCODING = 'UTF8'
    LC_COLLATE = 'Norwegian_Norway.1252'
    LC_CTYPE = 'Norwegian_Norway.1252'
    TABLESPACE = pg_default
    CONNECTION LIMIT = -1
    IS_TEMPLATE = False;
	
	
	
	create table if not exists stock(
	id integer primary key,
	symbol text not null UNIQUE,
	company text not NULl
	);
	create table if not exists stock_price(
	id integer primary key,
	stock_id integer
	date timestamp not null,
	volume int not null,
		FOREIGN KEY (stock_id) references stock(id)
	);
create table if not exists watchlist(
	id text,
	name text,
)
create table if not exists user(
	id text,
	username text not null
)

	
	
	