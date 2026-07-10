package library;

import library.Models.Loan;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Each test gets a fresh in-memory SQLite database (":memory:"), so
 * tests never touch a real file on disk, never interfere with each
 * other, and run fast.
 */
public class LibraryServiceTest {

    private Database db;
    private LibraryService service;

    @Before
    public void setUp() throws SQLException {
        db = new Database(":memory:");
        service = new LibraryService(db);
    }

    @After
    public void tearDown() throws SQLException {
        db.close();
    }

    // -----------------------------------------------------------
    // Basic catalog / member creation
    // -----------------------------------------------------------

    @Test
    public void addBook_returnsIncrementingIds() throws SQLException {
        int id1 = service.addBook("Book A", "Author A", "ISBN-A", 1);
        int id2 = service.addBook("Book B", "Author B", "ISBN-B", 1);
        assertEquals(1, id1);
        assertEquals(2, id2);
    }

    @Test
    public void newBook_hasFullAvailability() throws SQLException {
        int bookId = service.addBook("Book A", "Author A", "ISBN-A", 3);
        assertEquals(3, service.availableCopies(bookId));
    }

    // -----------------------------------------------------------
    // Checkout business rules
    // -----------------------------------------------------------

    @Test
    public void checkout_reducesAvailableCopies() throws SQLException {
        int bookId = service.addBook("Book A", "Author A", "ISBN-A", 1);
        int memberId = service.addMember("Alice", "alice@example.com", 3);

        service.checkoutBook(memberId, bookId, LocalDate.now());

        assertEquals(0, service.availableCopies(bookId));
    }

    @Test(expected = LibraryService.BookUnavailableException.class)
    public void checkout_failsWhenNoCopiesAvailable() throws SQLException {
        int bookId = service.addBook("Book A", "Author A", "ISBN-A", 1);
        int alice = service.addMember("Alice", "alice@example.com", 3);
        int bob = service.addMember("Bob", "bob@example.com", 3);

        service.checkoutBook(alice, bookId, LocalDate.now());
        // Second checkout should fail -- no copies left.
        service.checkoutBook(bob, bookId, LocalDate.now());
    }

    @Test(expected = LibraryService.LoanLimitExceededException.class)
    public void checkout_failsWhenMemberAtLoanLimit() throws SQLException {
        int memberId = service.addMember("Alice", "alice@example.com", 1); // limit of 1
        int book1 = service.addBook("Book A", "Author A", "ISBN-A", 5);
        int book2 = service.addBook("Book B", "Author B", "ISBN-B", 5);

        service.checkoutBook(memberId, book1, LocalDate.now());
        // Second checkout should fail -- member already at their limit.
        service.checkoutBook(memberId, book2, LocalDate.now());
    }

    @Test
    public void checkout_failureDoesNotPartiallyWriteToDatabase() throws SQLException {
        // Regression test for the transaction rollback: a failed
        // checkout (due to hitting the loan limit) must not leave a
        // half-written loan row behind, and must not have decremented
        // availability on the *second* book that was never actually
        // loaned out.
        int memberId = service.addMember("Alice", "alice@example.com", 1);
        int book1 = service.addBook("Book A", "Author A", "ISBN-A", 5);
        int book2 = service.addBook("Book B", "Author B", "ISBN-B", 5);

        service.checkoutBook(memberId, book1, LocalDate.now());
        try {
            service.checkoutBook(memberId, book2, LocalDate.now());
            fail("Expected LoanLimitExceededException");
        } catch (LibraryService.LoanLimitExceededException expected) {
            // expected
        }

        assertEquals(5, service.availableCopies(book2));
        assertEquals(1, service.activeLoanCount(memberId));
    }

    @Test
    public void dueDate_isFourteenDaysAfterCheckout() throws SQLException {
        int bookId = service.addBook("Book A", "Author A", "ISBN-A", 1);
        int memberId = service.addMember("Alice", "alice@example.com", 3);
        LocalDate checkoutDate = LocalDate.of(2026, 1, 1);

        service.checkoutBook(memberId, bookId, checkoutDate);

        List<Loan> loans = service.activeLoansForMember(memberId);
        assertEquals(1, loans.size());
        assertEquals("2026-01-15", loans.get(0).dueDate());
    }

    // -----------------------------------------------------------
    // Return logic
    // -----------------------------------------------------------

    @Test
    public void returnBook_restoresAvailability() throws SQLException {
        int bookId = service.addBook("Book A", "Author A", "ISBN-A", 1);
        int memberId = service.addMember("Alice", "alice@example.com", 3);
        int loanId = service.checkoutBook(memberId, bookId, LocalDate.now());

        service.returnBook(loanId, LocalDate.now());

        assertEquals(1, service.availableCopies(bookId));
        assertEquals(0, service.activeLoanCount(memberId));
    }

    @Test(expected = IllegalArgumentException.class)
    public void returnBook_failsIfAlreadyReturned() throws SQLException {
        int bookId = service.addBook("Book A", "Author A", "ISBN-A", 1);
        int memberId = service.addMember("Alice", "alice@example.com", 3);
        int loanId = service.checkoutBook(memberId, bookId, LocalDate.now());

        service.returnBook(loanId, LocalDate.now());
        service.returnBook(loanId, LocalDate.now()); // already returned -- should fail
    }

    // -----------------------------------------------------------
    // JOIN / reporting queries
    // -----------------------------------------------------------

    @Test
    public void activeLoansForMember_excludesReturnedLoans() throws SQLException {
        int book1 = service.addBook("Book A", "Author A", "ISBN-A", 1);
        int book2 = service.addBook("Book B", "Author B", "ISBN-B", 1);
        int memberId = service.addMember("Alice", "alice@example.com", 3);

        int loan1 = service.checkoutBook(memberId, book1, LocalDate.now());
        service.checkoutBook(memberId, book2, LocalDate.now());
        service.returnBook(loan1, LocalDate.now());

        List<Loan> active = service.activeLoansForMember(memberId);
        assertEquals(1, active.size());
        assertEquals("Book B", active.get(0).bookTitle());
    }

    @Test
    public void overdueLoans_findsOnlyPastDueUnreturnedLoans() throws SQLException {
        int book1 = service.addBook("Overdue Book", "Author A", "ISBN-A", 1);
        int book2 = service.addBook("Not Due Yet", "Author B", "ISBN-B", 1);
        int memberId = service.addMember("Alice", "alice@example.com", 3);

        // Checked out 20 days ago -- due date (14-day loan) was 6 days ago.
        service.checkoutBook(memberId, book1, LocalDate.now().minusDays(20));
        // Checked out today -- not due for 14 days.
        service.checkoutBook(memberId, book2, LocalDate.now());

        List<Loan> overdue = service.overdueLoans(LocalDate.now());
        assertEquals(1, overdue.size());
        assertEquals("Overdue Book", overdue.get(0).bookTitle());
    }

    @Test
    public void mostBorrowedBooks_ordersByBorrowCountDescending() throws SQLException {
        int popular = service.addBook("Popular Book", "Author A", "ISBN-A", 5);
        int unpopular = service.addBook("Unpopular Book", "Author B", "ISBN-B", 5);
        int m1 = service.addMember("Alice", "alice@example.com", 5);
        int m2 = service.addMember("Bob", "bob@example.com", 5);

        int loan1 = service.checkoutBook(m1, popular, LocalDate.now());
        service.returnBook(loan1, LocalDate.now());
        int loan2 = service.checkoutBook(m2, popular, LocalDate.now());
        service.returnBook(loan2, LocalDate.now());
        service.checkoutBook(m1, unpopular, LocalDate.now());

        List<Object[]> ranked = service.mostBorrowedBooks(2);
        assertEquals("Popular Book", ranked.get(0)[0]);
        assertEquals(2, ranked.get(0)[2]);
        assertEquals("Unpopular Book", ranked.get(1)[0]);
        assertEquals(1, ranked.get(1)[2]);
    }

    @Test
    public void searchBooksByTitle_isCaseInsensitiveAndPartialMatch() throws SQLException {
        service.addBook("Clean Code", "Robert Martin", "ISBN-1", 1);
        service.addBook("Clean Architecture", "Robert Martin", "ISBN-2", 1);
        service.addBook("The Pragmatic Programmer", "Andrew Hunt", "ISBN-3", 1);

        List<Models.Book> results = service.searchBooksByTitle("clean");
        assertEquals(2, results.size());
    }
}
