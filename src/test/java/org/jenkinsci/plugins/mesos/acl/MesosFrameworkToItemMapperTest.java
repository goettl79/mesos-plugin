package org.jenkinsci.plugins.mesos.acl;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;


/**
 * Created by seder on 07/04/16.
 */
public class MesosFrameworkToItemMapperTest {

  @Before
  public void setUp() throws Exception {

  }

  @After
  public void tearDown() throws Exception {

  }

  @Test
  public void findFrameworkName() throws Exception {
    MesosFrameworkToItemMapper mesosFrameworkToItemMapper = new MesosFrameworkToItemMapper();

    String expectedFrameworkName = "My Mesos Framework";
    String actualFrameworkName = mesosFrameworkToItemMapper.findFrameworkName("MyFolder/MyItem");

    //assertThat(actualFrameworkName, is(equalTo(mesosFrameworkToItemMapper.findFrameworkName("MyFolder/MyItem"))));
    Assert.assertEquals(expectedFrameworkName, actualFrameworkName);
  }

}
