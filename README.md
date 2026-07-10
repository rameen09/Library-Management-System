# Library Management System

A Java + SQL library management system with real relational schema
design, transactional business logic, and JOIN-based reporting queries
— tested with JUnit.

## What it does

Models a small library: books, members, and loans, with real
relationships between them (not just a flat table):

- **Checkout a book** — enforces two business rules in a single
  database transaction: the book must have an available copy, and the
  member must be under their personal loan limit. If either rule
  fails, the whole operation rolls back — nothing is partially
  written.
- **Return a book** — marks a loan as returned and frees up the copy.
- **Reporting queries**, all real SQL (not filtered in Java after the
  fact):
  - A member's currently active loans (JOIN across `loans`, `books`, `members`)
  - All overdue loans as of a given date
  - Most-borrowed books, ranked by borrow count (`GROUP BY` + aggregate)
  - Search books by title (partial match)

## Schema

```
books   (id, title, author, isbn, total_copies)
members (id, name, email, max_loans)
loans   (id, book_id -> books.id, member_id -> members.id,
         checkout_date, due_date, return_date)
```

`loans` is the join table connecting books and members, with foreign
keys enforced via `PRAGMA foreign_keys = ON`.

## Requirements

- JDK 17+
- SQLite JDBC driver, slf4j, and JUnit 4 jars in `lib/`

On Ubuntu/Debian, install and copy them with:
```bash
sudo apt-get install default-jdk libxerial-sqlite-jdbc-java junit4
cp /usr/share/java/sqlite-jdbc.jar lib/
cp /usr/share/java/slf4j-api.jar lib/
cp /usr/share/java/slf4j-nop.jar lib/
cp /usr/share/java/junit4.jar lib/
cp /usr/share/java/hamcrest-core.jar lib/
```

## Build & run

```bash
./build.sh          # compiles everything and runs the 13 JUnit tests
./build.sh run       # runs the Main demo (checks books in/out, prints reports)
```

## Example demo output

```
Checked out 'Clean Code' to Rameen, loan id: 1
Checked out 'The Pragmatic Programmer' to Rameen
Expected failure: Book 2 has no available copies

Rameen's active loans:
  Clean Code (due 2026-07-24)
  The Pragmatic Programmer (due 2026-07-24)

Returned 'Clean Code'.

Most borrowed books:
  Clean Code by Robert C. Martin -- borrowed 1 time(s)
  The Pragmatic Programmer by Andrew Hunt -- borrowed 1 time(s)
```

## Tests

13 JUnit tests, each running against a fresh in-memory SQLite database
(`:memory:`) so tests never touch disk or interfere with each other.
Covers:
- Checkout/return correctness
- Both business rules (loan limits, copy availability)
- Transaction rollback on failure (a failed checkout must not
  partially modify the database)
- Due date calculation
- JOIN-based active-loan and overdue-loan queries
- GROUP BY aggregate ranking query
- Search

## Project structure

```
src/main/java/library/
  Database.java         Connection setup + schema creation
  Models.java            Record types (Book, Member, Loan)
  LibraryService.java     Business logic, transactions, JOIN queries
  Main.java               End-to-end demo
src/test/java/library/
  LibraryServiceTest.java 13 JUnit tests
build.sh                  Compile/test/run script
```
