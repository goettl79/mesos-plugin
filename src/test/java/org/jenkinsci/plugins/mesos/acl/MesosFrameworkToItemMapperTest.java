package org.jenkinsci.plugins.mesos.acl;

import hudson.model.Failure;
import hudson.model.ManagementLink;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.mesos.MesosCloud;
import org.jenkinsci.plugins.mesos.Messages;
import org.jenkinsci.plugins.mesos.config.MesosPluginConfiguration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.JenkinsRule;
import org.kohsuke.stapler.StaplerRequest;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.doReturn;


@RunWith(MockitoJUnitRunner.class)
public class MesosFrameworkToItemMapperTest {

  @Rule
  final public JenkinsRule jenkins = new JenkinsRule();


  private MesosFrameworkToItemMapper mapper;

  @Before
  public void setUp() {
    mapper = ManagementLink.all().get(MesosPluginConfiguration.class).getMapper();
  }

  @Test
  public void returnDenyAsDefaultFrameworkNameAndEmptyACLEntriesWithNoConfiguration() throws Exception {
    String expectedFrameworkName = "Deny";
    String actualFrameworkName = mapper.getDefaultFrameworkName();
    List<ACLEntry> actualACLEntries = mapper.getACLEntries();

    assertThat(actualFrameworkName, is(equalTo(expectedFrameworkName)));
    assertThat(actualACLEntries.isEmpty(), is(true));
  }

  @Test
  public void findFrameworkNameReturnsDenyAsDefaultFrameworkNameWithoutConfiguration() throws Exception {
    String expectedFrameworkName = "Deny";
    String actualDefaultFrameworkName = mapper.getDefaultFrameworkName();
    String actualFrameworkName = mapper.findFrameworkName("ArbitraryFolder/ArbitraryItem");

    assertThat(actualDefaultFrameworkName, is(equalTo(expectedFrameworkName)));
    assertThat(actualFrameworkName, is(equalTo(expectedFrameworkName)));
  }

  @Test
  public void findFrameworkNameReturnsConfiguredDefaultFrameworkName() throws Exception {
    // Prepare
    MesosCloud mockMesosCloud = Mockito.mock(MesosCloud.class);
    doReturn("My Framework").when(mockMesosCloud).getFrameworkName();
    jenkins.getInstance().clouds.add(mockMesosCloud);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "My Framework");
    json.put("aclEntries", new JSONArray());

    mapper.getDescriptorImpl().configure(mockReq, json);

    // Actual Test
    String expectedFrameworkName = "My Framework";
    String actualDefaultFrameworkName = mapper.getDefaultFrameworkName();
    String actualFrameworkName = mapper.findFrameworkName("MyItem/myJob");

    assertThat(actualDefaultFrameworkName, is(equalTo(expectedFrameworkName)));
    assertThat(actualFrameworkName, is(equalTo(expectedFrameworkName)));
  }

  @Test
  public void findFrameworkNameReturnsConfiguredFrameworkNameOfACLEntries() throws Exception {
    // Prepare
    MesosCloud mockMesosCloud = Mockito.mock(MesosCloud.class);
    doReturn("My Framework").when(mockMesosCloud).getFrameworkName();
    jenkins.getInstance().clouds.add(mockMesosCloud);

    List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    aclEntries.add(new ACLEntry("MyFolder/.*", "My Framework"));

    JSONArray aclEntriesJSON = JSONArray.fromObject(aclEntries);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "Deny");
    json.put("aclEntries", aclEntriesJSON);

    doReturn(aclEntries).when(mockReq).bindJSONToList(ACLEntry.class, aclEntriesJSON);

    mapper.getDescriptorImpl().configure(mockReq, json);

    // Actual Test
    String expectedFrameworkName = "My Framework";
    String expectedDefaultFrameworkName = "Deny";
    String actualFrameworkName = mapper.findFrameworkName("MyFolder/myJob");
    String actualDefaultFrameworkName = mapper.getDefaultFrameworkName();

    assertThat(actualDefaultFrameworkName, is(equalTo(expectedDefaultFrameworkName)));
    assertThat(actualFrameworkName, is(equalTo(expectedFrameworkName)));
  }

  @Test
  public void findFrameworkNameReturnsDefaultFrameworkNameWhenNoACLMatches() throws Exception {
    // Prepare
    MesosCloud mockMesosCloud = Mockito.mock(MesosCloud.class);
    doReturn("My Framework").when(mockMesosCloud).getFrameworkName();
    jenkins.getInstance().clouds.add(mockMesosCloud);

    List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    aclEntries.add(new ACLEntry("MyFolder/.*", "My Framework"));

    JSONArray aclEntriesJSON = JSONArray.fromObject(aclEntries);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "Deny");
    json.put("aclEntries", aclEntriesJSON);

    doReturn(aclEntries).when(mockReq).bindJSONToList(ACLEntry.class, aclEntriesJSON);

    mapper.getDescriptorImpl().configure(mockReq, json);

    // Actual Test
    String expectedFrameworkName = "Deny";
    String expectedDefaultFrameworkName = "Deny";
    String actualFrameworkName = mapper.findFrameworkName("MyOtherFolder/MyJob");
    String actualDefaultFrameworkName = mapper.getDefaultFrameworkName();

    assertThat(actualDefaultFrameworkName, is(equalTo(expectedDefaultFrameworkName)));
    assertThat(actualFrameworkName, is(equalTo(expectedFrameworkName)));
  }

  @Test(expected = Failure.class)
  public void configureFailsWithDefaultMesosFrameworkNotFound() throws Exception {
    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "My Framework");
    json.put("aclEntries", new JSONArray());

    mapper.getDescriptorImpl().configure(mockReq, json);
  }

  @Test(expected = Failure.class)
  public void configureFailsWithACLEntryMesosFrameworkNotFound() throws Exception {
    List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    aclEntries.add(new ACLEntry("MyFolder/.*", "My Unconfigured Framework"));

    JSONArray aclEntriesJSON = JSONArray.fromObject(aclEntries);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "Deny");
    json.put("aclEntries", aclEntriesJSON);

    doReturn(aclEntries).when(mockReq).bindJSONToList(ACLEntry.class, aclEntriesJSON);

    mapper.getDescriptorImpl().configure(mockReq, json);
  }

  @Test(expected = Failure.class)
  public void configureFailsWithInvalidRegexForItemPattern() throws Exception {
    List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    aclEntries.add(new ACLEntry("MyInvalidRegexp/.**", "Deny"));

    JSONArray aclEntriesJSON = JSONArray.fromObject(aclEntries);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "Deny");
    json.put("aclEntries", aclEntriesJSON);

    doReturn(aclEntries).when(mockReq).bindJSONToList(ACLEntry.class, aclEntriesJSON);

    mapper.getDescriptorImpl().configure(mockReq, json);
  }


  @Test
  public void addACLEntriesSuccessfullyAddsTheSpecifiedEntry() throws Exception {
    // preconfigure acl entries
    List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    aclEntries.add(new ACLEntry("MyFolder/.*", "Deny"));
    aclEntries.add(new ACLEntry("MyOtherFolder/.*", "Deny"));

    JSONArray aclEntriesJSON = JSONArray.fromObject(aclEntries);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "Deny");
    json.put("aclEntries", aclEntriesJSON);

    doReturn(aclEntries).when(mockReq).bindJSONToList(ACLEntry.class, aclEntriesJSON);
    mapper.getDescriptorImpl().configure(mockReq, json);

    // actual test
    String itemPattern = "MyNewFancyFolder/.*";
    String frameworkName = "Deny";
    ACLEntry excpectedACLEntry = new ACLEntry(itemPattern, frameworkName);
    List<ACLEntry> expectedACLEntries = new ArrayList<ACLEntry>(aclEntries);
    expectedACLEntries.add(excpectedACLEntry);

    ACLEntry actualACLEntry = mapper.getDescriptorImpl().addACLEntry(itemPattern, frameworkName);
    List<ACLEntry> actualACLEntries = mapper.getDescriptorImpl().getACLEntries();

    assertThat(actualACLEntry, is(equalTo(excpectedACLEntry)));
    assertThat(actualACLEntries, hasItem(excpectedACLEntry));
    assertThat(actualACLEntries, is(equalTo(expectedACLEntries)));
  }

  @Test
  public void addACLEntriesFailsBecauseEntryWithEqualItemPatternExists() throws Exception {
    // preconfigure acl entries
    List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    aclEntries.add(new ACLEntry("MyFolder/.*", "Deny"));
    aclEntries.add(new ACLEntry("MyOtherFolder/.*", "Deny"));

    JSONArray aclEntriesJSON = JSONArray.fromObject(aclEntries);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "Deny");
    json.put("aclEntries", aclEntriesJSON);

    doReturn(aclEntries).when(mockReq).bindJSONToList(ACLEntry.class, aclEntriesJSON);
    mapper.getDescriptorImpl().configure(mockReq, json);

    // actual test
    String itemPattern = "MyFolder/.*";
    String frameworkName = "Does not matter";
    String expectedFailureMessage = Messages.MesosFrameworkToItemMapper_MultipleACLEntriesWithEqualPattern(itemPattern);

    try {
      mapper.getDescriptorImpl().addACLEntry(itemPattern, frameworkName);
    } catch (Failure e) {
      assertThat(e.getMessage(), is(equalTo(expectedFailureMessage)));
    }

    List<ACLEntry> actualACLEntries = mapper.getDescriptorImpl().getACLEntries();
    assertThat(actualACLEntries, is(equalTo(aclEntries)));
  }

  @Test
  public void removeACLEntriesSuccessfullyRemovesTheEntryWithTheSpecifiedPattern() throws Exception {
    // preconfigure acl entries
    List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    aclEntries.add(new ACLEntry("MyFolder/.*", "Deny"));
    aclEntries.add(new ACLEntry("MyOtherFolder/.*", "Deny"));

    JSONArray aclEntriesJSON = JSONArray.fromObject(aclEntries);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "Deny");
    json.put("aclEntries", aclEntriesJSON);

    doReturn(aclEntries).when(mockReq).bindJSONToList(ACLEntry.class, aclEntriesJSON);
    mapper.getDescriptorImpl().configure(mockReq, json);

    // actual test
    String itemPattern = "MyFolder/.*";
    String frameworkName = "Deny";
    ACLEntry excpectedACLEntry = new ACLEntry(itemPattern, frameworkName);
    List<ACLEntry> expectedACLEntries = new ArrayList<ACLEntry>(aclEntries);
    expectedACLEntries.remove(excpectedACLEntry);

    ACLEntry actualACLEntry = mapper.getDescriptorImpl().removeACLEntry(itemPattern);
    List<ACLEntry> actualACLEntries = mapper.getDescriptorImpl().getACLEntries();

    assertThat(actualACLEntry, is(equalTo(excpectedACLEntry)));
    assertThat(actualACLEntries, not(hasItem(excpectedACLEntry)));
    assertThat(actualACLEntries, is(equalTo(expectedACLEntries)));
  }

  @Test
  public void removeACLEntriesDoesNotRemoveNonExistingItemPattern() throws Exception {
    // preconfigure acl entries
    List<ACLEntry> aclEntries = new ArrayList<ACLEntry>();
    aclEntries.add(new ACLEntry("MyFolder/.*", "Deny"));
    aclEntries.add(new ACLEntry("MyOtherFolder/.*", "Deny"));

    JSONArray aclEntriesJSON = JSONArray.fromObject(aclEntries);

    StaplerRequest mockReq = Mockito.mock(StaplerRequest.class);
    JSONObject json = new JSONObject();
    json.put("defaultFrameworkName", "Deny");
    json.put("aclEntries", aclEntriesJSON);

    doReturn(aclEntries).when(mockReq).bindJSONToList(ACLEntry.class, aclEntriesJSON);
    mapper.getDescriptorImpl().configure(mockReq, json);

    // actual test
    String itemPattern = "MyNonExistingFolder/.*";
    List<ACLEntry> expectedACLEntries = new ArrayList<ACLEntry>(aclEntries);

    ACLEntry actualACLEntry = mapper.getDescriptorImpl().removeACLEntry(itemPattern);
    List<ACLEntry> actualACLEntries = mapper.getDescriptorImpl().getACLEntries();

    assertThat(actualACLEntry, is(nullValue()));
    assertThat(actualACLEntries, is(equalTo(expectedACLEntries)));
  }

  @Test
  public void changeDefaultFrameworkNameChangesToTheSpecifiedFramework() throws Exception {
    // preconfigure acl entries
    MesosCloud mockMesosCloud = Mockito.mock(MesosCloud.class);
    doReturn("My Framework").when(mockMesosCloud).getFrameworkName();
    jenkins.getInstance().clouds.add(mockMesosCloud);

    // actual test
    String expectedNewDefaultFramework = "My Framework";
    String expectedOldDefaultFramework = "Deny";

    String actualOldDefaultFramework = mapper.getDescriptorImpl().changeDefaultFrameworkName(expectedNewDefaultFramework);
    String actualNewDefaultFramework = mapper.getDescriptorImpl().getDefaultFrameworkName();

    assertThat(actualOldDefaultFramework, is(equalTo(expectedOldDefaultFramework)));
    assertThat(actualNewDefaultFramework, is(equalTo(expectedNewDefaultFramework)));
  }

  @Test
  public void changeDefaultFrameworkNameFailsBecauseOfNonExistingFramework() throws Exception {
    // preconfigure acl entries
    MesosCloud mockMesosCloud = Mockito.mock(MesosCloud.class);
    doReturn("My Framework").when(mockMesosCloud).getFrameworkName();
    jenkins.getInstance().clouds.add(mockMesosCloud);

    // actual test
    String newDefaultFramework = "My Non Existing Framework";
    String expectedFailureMessage = Messages.MesosFrameworkToItemMapper_NonExistingFramework(newDefaultFramework);
    String expectedDefaultFramework = "Deny";

    try {
      mapper.getDescriptorImpl().changeDefaultFrameworkName(newDefaultFramework);
    } catch (Failure e) {
      assertThat(e.getMessage(), is(equalTo(expectedFailureMessage)));
    }

    String actualDefaultFramework = mapper.getDescriptorImpl().getDefaultFrameworkName();
    assertThat(actualDefaultFramework, is(equalTo(expectedDefaultFramework)));
  }
}
