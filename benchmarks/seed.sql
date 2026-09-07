CREATE TABLE bench_people (
    id integer PRIMARY KEY,
    email text NOT NULL,
    name text NOT NULL,
    score integer NOT NULL
);

INSERT INTO bench_people (id, email, name, score)
SELECT i, 'person' || i || '@example.com', 'Person ' || i, i % 100
FROM generate_series(1, 10000) AS i;

ANALYZE bench_people;
