package org.jenkinsci.plugins.mesos.acl;

import org.junit.Test;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;

public class ACLEntryTest {


  @Test
  public void equalsReturnsTrueForACLEntriesWithEqualItemPatterns() throws Exception {
    ACLEntry actualACLEntry = new ACLEntry("MyFolder/.*", "My Framework");
    ACLEntry expectedACLEntry = new ACLEntry("MyFolder/.*", "The Framework Should not matter");

    assertThat(actualACLEntry, is(equalTo(expectedACLEntry)));
  }

  @Test
  public void equalsReturnsTrueForSameACLEntry() throws Exception {
    ACLEntry aclEntry = new ACLEntry("MyFolder/.*", "My Framework");

    assertThat(aclEntry, is(equalTo(aclEntry)));
  }

  @Test
  public void equalsReturnsFalseForACLEntriesWithDifferentItemPatterns() throws Exception {
    ACLEntry actualACLEntry = new ACLEntry("MyFolder/.*", "My Framework");
    ACLEntry expectedACLEntry = new ACLEntry("MyOtherFolder/.*", "The Framework Should not matter");

    assertThat(actualACLEntry, is(not(equalTo(expectedACLEntry))));
  }

  @Test
  public void equalsReturnsFalseForNullValue() throws Exception {
    ACLEntry actualACLEntry = new ACLEntry("MyFolder/.*", "My Framework");

    assertThat(actualACLEntry, is(not(equalTo(null))));
  }

  @Test
  public void equalsReturnsFalseForOtherObject() throws Exception {
    ACLEntry actualACLEntry = new ACLEntry("MyFolder/.*", "My Framework");

    assertThat(actualACLEntry, is(not(equalTo(new Object()))));
  }

  @Test
  public void toStringReturnsStringWithoutComplexClassName() throws Exception {
    String actualACLEntryToString = new ACLEntry("MyFolder/.*", "My Framework").toString();
    String expectedACLEntryToString = "ACLEntry[itemPattern=MyFolder/.*,frameworkName=My Framework]";


    assertThat(actualACLEntryToString, is(equalTo(expectedACLEntryToString)));
  }

}
