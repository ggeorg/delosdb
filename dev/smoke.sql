connect 'jdbc:derby:build/smoke-db/delosdb;create=true';
create table smoke_test(id int primary key, name varchar(32));
insert into smoke_test values (1, 'ok');
select * from smoke_test;
drop table smoke_test;
disconnect;
exit;
