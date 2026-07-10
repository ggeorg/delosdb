-- DELOSDB_SQLANCER_PROFILE_SKELETON
-- Minimal mixed heap/MVCC smoke workload for external SQLancer command wiring.
-- This file is a profile contract, not a normal DelosDB build input.

create table heap_baseline (
    id int primary key,
    payload varchar(64),
    amount int
);

create table mvcc_candidate (
    id int primary key,
    payload varchar(64),
    amount int
) using delos_mvcc;

create index heap_baseline_amount_idx on heap_baseline(amount);
create index mvcc_candidate_amount_idx on mvcc_candidate(amount);

insert into heap_baseline values (1, 'alpha', 10), (2, 'beta', 20), (3, null, 30);
insert into mvcc_candidate values (1, 'alpha', 10), (2, 'beta', 20), (3, null, 30);
commit;

update heap_baseline set amount = amount + 5 where id in (1, 2);
update mvcc_candidate set amount = amount + 5 where id in (1, 2);
commit;

select id, payload, amount from heap_baseline where amount >= 15 order by id;
select id, payload, amount from mvcc_candidate where amount >= 15 order by id;

insert into heap_baseline values (99, 'rolled-back', 99);
insert into mvcc_candidate values (99, 'rolled-back', 99);
rollback;

select count(*) from heap_baseline where id = 99;
select count(*) from mvcc_candidate where id = 99;
