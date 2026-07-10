package library;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Handles the SQLite connection and creates the schema if it doesn't
 * already exist. The schema models a small library system with three
 * related tables:
 *
 *   books   -- the catalog
 *   members -- registered library members
 *   loans   -- join table recording which member borrowed which book,
 *              and whether/when it was returned
 *
 * Foreign keys tie loans back to books and members, so queries about
 * "what does this member have checked out" or "is this book available"
 * require real relational JOINs rather than a single flat table.
 */
public class Database {

    private final Connection connection;

    public Database(String dbPath) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        // Foreign key constraints are off by default in SQLite -- must
        // be explicitly enabled per connection.
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
        }
        createSchema();
    }

    public Connection getConnection() {
        return connection;
    }

    private void createSchema() throws SQLException {
        String books = """
            CREATE TABLE IF NOT EXISTS books (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                isbn TEXT UNIQUE NOT NULL,
                total_copies INTEGER NOT NULL CHECK (total_copies > 0)
            )
            """;

        String members = """
            CREATE TABLE IF NOT EXISTS members (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                email TEXT UNIQUE NOT NULL,
                max_loans INTEGER NOT NULL DEFAULT 3
            )
            """;

        String loans = """
            CREATE TABLE IF NOT EXISTS loans (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                book_id INTEGER NOT NULL,
                member_id INTEGER NOT NULL,
                checkout_date TEXT NOT NULL,
                due_date TEXT NOT NULL,
                return_date TEXT,
                FOREIGN KEY (book_id) REFERENCES books(id),
                FOREIGN KEY (member_id) REFERENCES members(id)
            )
            """;

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(books);
            stmt.execute(members);
            stmt.execute(loans);
        }
    }

    public void close() throws SQLException {
        connection.close();
    }
}
