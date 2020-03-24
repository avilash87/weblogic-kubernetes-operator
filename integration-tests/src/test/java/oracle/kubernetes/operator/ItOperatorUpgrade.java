// Copyright (c) 2019, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import oracle.kubernetes.operator.utils.Domain;
import oracle.kubernetes.operator.utils.ExecResult;
import oracle.kubernetes.operator.utils.LoggerHelper;
import oracle.kubernetes.operator.utils.Operator;
import oracle.kubernetes.operator.utils.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.Alphanumeric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Operator upgrade JUnit test file testing the operator upgrade from older releases to develop.
 */
@TestMethodOrder(Alphanumeric.class)
public class ItOperatorUpgrade extends BaseTest {
  private static String OP_TARGET_RELEASE = "weblogic-kubernetes-operator:latest";
  private static String OP_NS = "";
  private static String OP_DEP_NAME = "";
  private static String OP_SA = "";
  private static String DOM_NS = "";
  private static String DUID = "";
  private static String M1_CTS = "";
  private static String M2_CTS = "";
  private static String ADMIN_CTS = "";
  private static String opUpgradeTmpDir;
  private Domain domain = null;
  private static Operator operator;
  boolean testCompletedSuccessfully = false;
  static String testClassName = null;
  private static StringBuffer namespaceList;

  /**
   * This method gets called only once before any of the test methods are executed.
   *
   * @throws Exception exception
   */
  @BeforeAll
  public static void staticPrepare() throws Exception {
    namespaceList = new StringBuffer();
    testClassName = new Object() {
    }.getClass().getEnclosingClass().getSimpleName();
    initialize(APP_PROPS_FILE, testClassName);
  }

  /**
   * Releases k8s cluster lease, archives result, pv directories.
   *
   * @throws Exception exception
   */
  @AfterAll
  public static void staticUnPrepare() throws Exception {
    tearDown(new Object() {
    }.getClass().getEnclosingClass().getSimpleName(), namespaceList.toString());
  }


  /**
   * This method gets called before every test. It creates the result/pv root directories
   * for the test. Creates the operator and domain if its not running.
   *
   * @throws Exception exception if result/pv/operator/domain creation fails
   */
  @BeforeEach
  public void prepare() throws Exception {
    createResultAndPvDirs(testClassName);
    if (System.getenv("IMAGE_NAME_OPERATOR") != null
        && System.getenv("IMAGE_TAG_OPERATOR") != null) {
      OP_TARGET_RELEASE = System.getenv("IMAGE_NAME_OPERATOR") + ":"
          + System.getenv("IMAGE_TAG_OPERATOR");
    }
  }

  /**
   * cleanup the domain and operator after every test.
   *
   * @throws Exception when domain and operator cleanup fails
   */
  @AfterEach
  public void cleanupOperatorAndDomain() throws Exception {
    if (testCompletedSuccessfully) {
      LoggerHelper.getLocal().log(Level.INFO, "+++++++++++++++Beginning AfterTest cleanup+++++++++++++++++++++");
      if (domain != null) {
        //domain.destroy();
        TestUtils.deleteWeblogicDomainResources(domain.getDomainUid());
        TestUtils.verifyAfterDeletion(domain);
        domain.deleteImage();
      }
      if (operator != null) {
        operator.destroy();
      }
      TestUtils.exec("rm -rf " + Paths.get(opUpgradeTmpDir).toString());
      TestUtils.exec("kubectl delete crd domains.weblogic.oracle --ignore-not-found");
      //ExecResult result = cleanup();
      LoggerHelper.getLocal().log(Level.INFO, "+++++++++++++++Done AfterTest cleanup+++++++++++++++++++++");
    }
  }

  /**
   * Test for upgrading Operator from release 2.5.0 to develop branch.
   *
   * @throws Exception when upgrade fails
   */
  @Test
  public void testOperatorUpgradeFrom2_5_0() throws Exception {
    Assumptions.assumeTrue(QUICKTEST);
    testCompletedSuccessfully = false;
    String testMethod = new Object() {
    }.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethod);
    OP_NS = "weblogic-operator250";
    DOM_NS = "weblogic-domain250";
    namespaceList.append(OP_NS);
    namespaceList.append(" ").append(DOM_NS);
    OP_DEP_NAME = "operator-upgrade250";
    OP_SA = "operator-sa250";
    DUID = "operatordomain250";
    setupOperatorAndDomain("release/2.5.0", "2.5.0");

    M1_CTS = TestUtils.getCreationTimeStamp(DOM_NS,DUID + "-managed-server1");
    M2_CTS = TestUtils.getCreationTimeStamp(DOM_NS,DUID + "-managed-server2");
    ADMIN_CTS = TestUtils.getCreationTimeStamp(DOM_NS,DUID + "-admin-server");
    upgradeOperator();
    testCompletedSuccessfully = true;
    LoggerHelper.getLocal().log(Level.INFO, "SUCCESS - " + testMethod);
  }

  /**
   * Upgrades operator to develop branch by using the helm upgrade.
   *
   * @throws Exception when upgrade fails or basic usecase testing or scaling fails.
   */
  private void upgradeOperator() throws Exception {
    operator.callHelmUpgrade("image=" + OP_TARGET_RELEASE);
    checkCrdVersion();
    System.out.println("Before Upgrade M1CTS[" + M1_CTS + "]");
    System.out.println("Before Upgrade M2CTS[" + M2_CTS + "]");
    System.out.println("Before Upgrade ADMINCTS[" + ADMIN_CTS + "]");
    checkDomainNotRestarted();
    testClusterScaling(operator, domain, false);
  }

  /**
   * Checks the expected Upgraded Version of CustomResourceDefintion (CRD) 
   * in default NameSpace in a loop. 
   *
   * @throws Exception when version does not match
   */
  private void checkCrdVersion() throws Exception {
    boolean result = false;
    LoggerHelper.getLocal().log(
        Level.INFO,
        "Checking for the CRD Version "
            + getDomainApiVersion()
            + " in a loop for up to 15 minutes");
    for (int i = 0; i < BaseTest.getMaxIterationsPod(); i++) {
      ExecResult exec =
          TestUtils.exec(
              "kubectl get crd domains.weblogic.oracle -o jsonpath='{.spec.versions[?(@.storage==true)].name}'", true);
      if (exec.stdout().contains(getCrdVersion())) {
        LoggerHelper.getLocal().log(Level.INFO, "Got expected CRD Version");
        result = true;
        break;
      }
      Thread.sleep(BaseTest.getWaitTimePod() * 1000);
    }
    if (!result) {
      throw new Exception("FAILURE: Didn't get expected CRD Version");
    }
  }

  /**
   * Check whether the weblogic server instances are rolling restarted.
   *
   * @throws Exception If restart fails or not restarted
   */
  private void checkDomainRollingRestarted() throws Exception {
    domain.verifyAdminServerRestarted();
    TestUtils.checkPodReady(DUID + "-" + domain.getAdminServerName(), DOM_NS);
    for (int i = 2; i >= 1; i--) {
      LoggerHelper.getLocal().log(Level.INFO,
          "Checking if managed server pod(" + DUID + "--managed-server" + i + ") is restarted");
      TestUtils.checkPodTerminating(DUID + "-managed-server" + i, DOM_NS);
      TestUtils.checkPodCreated(DUID + "-managed-server" + i, DOM_NS);
      TestUtils.checkPodReady(DUID + "-managed-server" + i, DOM_NS);
    }
  }

  /**
   * Check whether the weblogic server instances are still RUNNING 
   * not restarted due to Operator Upgrade by comparing the creationTimestamp
   * before and after upgrade
   *
   * @throws Exception If restarted
   */
  private void checkDomainNotRestarted() throws Exception {
    TestUtils.checkPodReady(DUID + "-" + domain.getAdminServerName(), DOM_NS);
    for (int i = 2; i >= 1; i--) {
     TestUtils.checkPodReady(DUID + "-managed-server" + i, DOM_NS);
    }
    String m1 = TestUtils.getCreationTimeStamp(DOM_NS,DUID + "-managed-server1");
    String m2 = TestUtils.getCreationTimeStamp(DOM_NS,DUID + "-managed-server2");
    String admin = TestUtils.getCreationTimeStamp(DOM_NS,DUID + "-admin-server");
    System.out.println("After Upgrade M1CTS[" + m1 + "]");
    System.out.println("After Upgrade M2CTS[" + m2 + "]");
    System.out.println("After Upgrade M2CTS[" + admin + "]");

    Assertions.assertEquals(M1_CTS, m1);
    Assertions.assertEquals(M2_CTS, m2);
    Assertions.assertEquals(ADMIN_CTS, admin);
  }

  /**
   * Creates operator based on operatorRelease passed to it and then creates a WebLogic domain
   * controlled by that operator.
   *
   * @param operatorGitRelease Git branch name of the operator release version
   * @param operatorRelease    Operator release version from the
   *                           https://hub.docker.com/r/oracle/weblogic-kubernetes-operator/tags
   * @throws Exception when operator or domain creation fails
   */
  private void setupOperatorAndDomain(String operatorGitRelease, String operatorRelease)
      throws Exception {
    LoggerHelper.getLocal().log(Level.INFO, "+++++++++++++++Beginning Test Setup+++++++++++++++++++++");
    opUpgradeTmpDir = getResultDir() + "/operatorupgrade";
    TestUtils.exec("rm -rf " + Paths.get(opUpgradeTmpDir).toString());
    Files.createDirectories(Paths.get(opUpgradeTmpDir));
    Map<String, Object> operatorMap = createOperatorMap(getNewSuffixCount(), true, "");
    operatorMap.put("operatorImageName", "oracle/weblogic-kubernetes-operator");
    operatorMap.put("operatorImageTag", operatorRelease);
    operatorMap.put("operatorGitVersion", operatorGitRelease);
    operatorMap.put("operatorGitVersionDir", opUpgradeTmpDir);
    operatorMap.put("namespace", OP_NS);
    operatorMap.put("releaseName", OP_DEP_NAME);
    operatorMap.put("serviceAccount", OP_SA);
    operatorMap.put("externalRestEnabled", true);
    List<String> domNs = new ArrayList<String>();
    domNs.add(DOM_NS);
    operatorMap.put("domainNamespaces", domNs);
    operator = TestUtils.createOperator(operatorMap, Operator.RestCertType.LEGACY);
    Map<String, Object> wlstDomainMap = createDomainMap(getNewSuffixCount(),testClassName);
    wlstDomainMap.put("domainUID", DUID);
    wlstDomainMap.put("namespace", DOM_NS);
    wlstDomainMap.put("projectRoot", opUpgradeTmpDir + "/weblogic-kubernetes-operator");
    domain = TestUtils.createDomain(wlstDomainMap);
    domain.verifyPodsCreated();
    domain.verifyServicesCreated();
    domain.verifyServersReady();
    LoggerHelper.getLocal().log(Level.INFO, "+++++++++++++++Ending Test Setup+++++++++++++++++++++");
  }

}
