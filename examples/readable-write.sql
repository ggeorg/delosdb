-- DelosDB readable-engine write demonstration.
-- One INSERT into a heap table and one into a delos_mvcc table, observed through
-- Derby's diagnostic tables before and after COMMIT. See docs/READABLE-ENGINE.md,
-- "One write end to end".
maximumdisplaywidth 120;
connect 'jdbc:derby:build/readable-write-demo;create=true';
autocommit off;

create table readable_heap (id int primary key, note varchar(20));
create table readable_mvcc (id int primary key, note varchar(20)) using delos_mvcc;
commit;

-- Both writes run in one transaction.
insert into readable_heap values (1, 'heap row');
insert into readable_mvcc values (1, 'mvcc row');

-- The transaction is active and has written log records: FIRST_INSTANT is the
-- log position of its first record.
select type, status, first_instant from syscs_diag.transaction_table
    where status = 'ACTIVE' and type = 'UserTransaction';

-- The locks it holds until commit.
select type, mode, tablename, lockname, state from syscs_diag.lock_table
    order by tablename, type, lockname;

commit;

-- After commit the locks are released.
select count(*) as locks_after_commit from syscs_diag.lock_table;

-- The rows live on pages of each table's containers.
select conglomeratename, isindex, numallocatedpages, numfreepages
    from table (syscs_diag.space_table('APP', 'READABLE_HEAP')) t
    order by conglomeratename;

disconnect;
exit;
