-- DelosDB Phase 10 readable-engine demonstration.
-- The same SQL is compiled once for EXPLAIN and executed once for EXPLAIN ANALYZE.
maximumdisplaywidth 12000;
connect 'jdbc:derby:build/readable-engine-demo;create=true';
autocommit off;

create table readable_order (
    id int primary key,
    status varchar(12) not null,
    amount int not null
) using delos_mvcc;
create index readable_order_status_idx on readable_order(status);

insert into readable_order values
    (1, 'OPEN', 120),
    (2, 'PAID', 80),
    (3, 'OPEN', 220),
    (4, 'OPEN', 60);
commit;

-- Compile and render the selected plan without executing the target query.
explain select id, amount from readable_order
    --DERBY-PROPERTIES index=readable_order_status_idx
    where status = 'OPEN'
    order by id;

-- Execute the same selected query once and attach bounded runtime/storage evidence.
explain analyze select id, amount from readable_order
    --DERBY-PROPERTIES index=readable_order_status_idx
    where status = 'OPEN'
    order by id;

rollback;
drop table readable_order;
commit;
disconnect;
exit;
