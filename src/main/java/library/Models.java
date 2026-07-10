package library;

/**
 * Plain data-holder records returned by LibraryService queries.
 * Using Java records keeps these immutable and removes boilerplate
 * getters/setters/equals/hashCode.
 */
public class Models {

    public record Book(int id, String title, String author, String isbn, int totalCopies) {}

    public record Member(int id, String name, String email, int maxLoans) {}

    public record Loan(
            int id,
            int bookId,
            String bookTitle,
            int memberId,
            String memberName,
            String checkoutDate,
            String dueDate,
            String returnDate // null if not yet returned
    ) {
        public boolean isReturned() {
            return returnDate != null;
        }
    }
}
