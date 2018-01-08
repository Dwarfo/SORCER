/*
 * Copyright to the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sorcer.core.deploy;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.rioproject.opstring.OperationalString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sorcer.test.ProjectContext;
import org.sorcer.test.SorcerTestRunner;
import org.sorcer.test.TestsRequiringRio;
import sorcer.core.SorcerConstants;
import sorcer.service.Exertion;
import sorcer.service.Job;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static sorcer.eo.operator.*;

/**
 * @author Dennis Reedy
 */
@RunWith(SorcerTestRunner.class)
@ProjectContext("core/sorcer-int-tests/deploy-tests")
@Category(TestsRequiringRio.class)
public class DeployExertionTest extends DeploySetup implements SorcerConstants {
    private final static Logger logger = LoggerFactory.getLogger(DeployExertionTest.class.getName());

    @BeforeClass
    public static void before() throws Exception {
        verifySorcerRunning();
    }

    //@Category(TestsRequiringRio.class)
    @Test
    public void deployAndExec() throws Exception {
        Job f1 = JobUtil.createJob();
        assertTrue(f1.isProvisionable());
        Map<ServiceDeployment.Unique, List<OperationalString>> deployments = OperationalStringFactory.create(f1);
        String name = null;
        for(Map.Entry<ServiceDeployment.Unique, List<OperationalString>> entry : deployments.entrySet()) {
            if(entry.getValue().size()>0) {
                name = entry.getValue().get(0).getName();
                break;
            }
        }
        assertNotNull(name);
        //undeploy(name);
        verifyExertion(f1);
        /* Run it again to make sure that the existing deployment is used */
        verifyExertion(f1);
    }

    private void verifyExertion(Job job) throws Exception {
        System.out.println("Verifying "+job.getName()  );
    	long t0 = System.currentTimeMillis();
        Exertion out = exert(job);
        System.out.println("Waited "+(System.currentTimeMillis()-t0)+" millis for exerting: " + out.getName());
        assertNotNull(out);
        System.out.println("===> out: "+ upcontext(out));
        assertEquals(400.0, get(out, "f1/f3/result/y3"));

        ServiceDeployment deployment = (ServiceDeployment)out.getProcessSignature().getDeployment();
        assertNotNull(deployment);
        Collection<String> deploymentNames = deployment.getDeployedNames();
        assertTrue(deploymentNames.size()>0);
    }

}
