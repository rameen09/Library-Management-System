package library;

import library.Models.Book;
import library.Models.Loan;
import library.Models.Member;

import java.sql.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * LibraryService contains the actual business rules on top of the raw
 * schema -- this is the part that makes it more than a basic CRUD
 * wrapper:
 *
 *   - A book can only be checked out if it has an available copy
 *     (total_copies minus currently-active loans for that book).
 *   - A member can only check out a book if they're under their
 *     personal loan limit (max_loans).
 *   - Checkout is wrapped in a transaction so a partial failure never
 *     leaves the database in an inconsistent state.
 *   - Overdue and "currently borrowed" lookups use real SQL JOINs
 *     across books, members, and loans, rather than pulling
 *     everything into Java and filtering there.
 */
public class LibraryService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DEFAULT_LOAN_DAYS = 14;

    private final Connection conn;

    public LibraryService(Database db) {
        this.conn = db.getConnection();
    }

    // -------------------------------------------------------------
    // Catalog / member management
    // -------------------------------------------------------------

    public int addBook(String title, String author, String isbn, int totalCopies) throws SQLException {
        String sql = "INSERT INTO books (title, author, isbn, total_copies) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, title);
            ps.setString(2, author);
            ps.setString(3, isbn);
            ps.setInt(4, totalCopies);
            ps.executeUpdate();
        }
        return lastInsertId();
    }

    public int addMember(String name, String email, int maxLoans) throws SQLException {
        String sql = "INSERT INTO members (name, email, max_loans) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setInt(3, maxLoans);
            ps.executeUpdate();
        }
        return lastInsertId();
    }

    private int lastInsertId() throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // -------------------------------------------------------------
    // Availability checks
    // -------------------------------------------------------------

    /** Number of copies of a book not currently out on loan. */
    public int availableCopies(int bookId) throws SQLException {
        String sql = """
            SELECT b.total_copies - COALESCE(active.count, 0) AS available
            FROM books b
            LEFT JOIN (
                SELECT book_id, COUNT(*) AS count
                FROM loans
                WHERE return_date IS NULL AND book_id = ?
                GROUP BY book_id
            ) active ON active.book_id = b.id
            WHERE b.id = ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, bookId);
            ps.setInt(2, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No book with id " + bookId);
                }
                return rs.getInt("available");
            }
        }
    }

    /** Number of books this member currently has checked out (not yet returned). */
    public int activeLoanCount(int memberId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM loans WHERE member_id = ? AND return_date IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    // -------------------------------------------------------------
    // Checkout / return -- the core business logic
    // -------------------------------------------------------------

    public static class LoanLimitExceededException extends RuntimeException {
        public LoanLimitExceededException(String message) { super(message); }
    }

    public static class BookUnavailableException extends RuntimeException {
        public BookUnavailableException(String message) { super(message); }
    }

    /**
     * Checks out a book to a member, enforcing both business rules,
     * inside a single transaction. If either rule is violated, the
     * transaction is rolled back and nothing is written.
     */
    public int checkoutBook(int memberId, int bookId, LocalDate checkoutDate) throws SQLException {
        conn.setAutoCommit(false);
        try {
            Member member = getMember(memberId);
            int activeLoans = activeLoanCount(memberId);
            if (activeLoans >= member.maxLoans()) {
                throw new LoanLimitExceededException(
                        "Member " + memberId + " has reached their loan limit of " + member.maxLoans());
            }

            int available = availableCopies(bookId);
            if (available <= 0) {
                throw new BookUnavailableException("Book " + bookId + " has no available copies");
            }

            LocalDate dueDate = checkoutDate.plusDays(DEFAULT_LOAN_DAYS);
            String sql = """
                INSERT INTO loans (book_id, member_id, checkout_date, due_date, return_date)
                VALUES (?, ?, ?, ?, NULL)
                """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, bookId);
                ps.setInt(2, memberId);
                ps.setString(3, checkoutDate.format(FMT));
                ps.setString(4, dueDate.format(FMT));
                ps.executeUpdate();
            }
            int loanId = lastInsertId();

            conn.commit();
            return loanId;
        } catch (RuntimeException | SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    public void returnBook(int loanId, LocalDate returnDate) throws SQLException {
        String sql = "UPDATE loans SET return_date = ? WHERE id = ? AND return_date IS NULL";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, returnDate.format(FMT));
            ps.setInt(2, loanId);
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("Loan " + loanId + " not found or already returned");
            }
        }
    }

    // -------------------------------------------------------------
    // JOIN-based reporting queries
    // -------------------------------------------------------------

    private Member getMember(int memberId) throws SQLException {
        String sql = "SELECT id, name, email, max_loans FROM members WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, memberId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("No member with id " + memberId);
                }
                return new Member(rs.getInt("id"), rs.getString("name"),
                        rs.getString("email"), rs.getInt("max_loans"));
            }
        }
    }

    /** All currently-active (not yet returned) loans for a member, joined with book titles. */
    public List<Loan> activeLoansForMember(int memberId) throws SQLException {
        String sql = """
            SELECT l.id, l.book_id, b.title, l.member_id, m.name,
                   l.checkout_date, l.due_date, l.return_date
            FROM loans l
            JOIN books b ON b.id = l.book_id
            JOIN members m ON m.id = l.member_id
            WHERE l.member_id = ? AND l.return_date IS NULL
            ORDER BY l.due_date ASC
            """;
        return queryLoans(sql, memberId);
    }

    /** Every loan that is overdue as of the given date (not returned, due date has passed). */
    public List<Loan> overdueLoans(LocalDate asOf) throws SQLException {
        String sql = """
            SELECT l.id, l.book_id, b.title, l.member_id, m.name,
                   l.checkout_date, l.due_date, l.return_date
            FROM loans l
            JOIN books b ON b.id = l.book_id
            JOIN members m ON m.id = l.member_id
            WHERE l.return_date IS NULL AND l.due_date < ?
            ORDER BY l.due_date ASC
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, asOf.format(FMT));
            return mapLoans(ps.executeQuery());
        }
    }

    /**
     * Books ranked by how many times they've ever been borrowed --
     * a GROUP BY aggregate query joined against the catalog.
     */
    public List<Object[]> mostBorrowedBooks(int limit) throws SQLException {
        String sql = """
            SELECT b.title, b.author, COUNT(l.id) AS times_borrowed
            FROM books b
            LEFT JOIN loans l ON l.book_id = b.id
            GROUP BY b.id
            ORDER BY times_borrowed DESC
            LIMIT ?
            """;
        List<Object[]> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Object[]{
                            rs.getString("title"),
                            rs.getString("author"),
                            rs.getInt("times_borrowed")
                    });
                }
            }
        }
        return results;
    }

    public List<Book> searchBooksByTitle(String keyword) throws SQLException {
        String sql = "SELECT id, title, author, isbn, total_copies FROM books WHERE title LIKE ?";
        List<Book> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "%" + keyword + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Book(rs.getInt("id"), rs.getString("title"),
                            rs.getString("author"), rs.getString("isbn"), rs.getInt("total_copies")));
                }
            }
        }
        return results;
    }

    private List<Loan> queryLoans(String sql, int param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, param);
            return mapLoans(ps.executeQuery());
        }
    }

    private List<Loan> mapLoans(ResultSet rs) throws SQLException {
        List<Loan> loans = new ArrayList<>();
        while (rs.next()) {
            loans.add(new Loan(
                    rs.getInt("id"),
                    rs.getInt("book_id"),
                    rs.getString("title"),
                    rs.getInt("member_id"),
                    rs.getString("name"),
                    rs.getString("checkout_date"),
                    rs.getString("due_date"),
                    rs.getString("return_date")
            ));
        }
        return loans;
    }
}
