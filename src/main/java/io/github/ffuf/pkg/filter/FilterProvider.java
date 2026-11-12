package io.github.ffuf.pkg.filter;

import io.github.ffuf.pkg.ffuf.Response;

/** A matcher or filter test on a response. */
public interface FilterProvider {
    /** {@return true when the response satisfies this test} */
    boolean filter(Response response);

    /** {@return the test's value in text form, as the configuration output shows it} */
    String repr();

    /** {@return the description shown in the banner, such as "Response status: 200"} */
    String reprVerbose();
}
