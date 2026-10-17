package byteman.demo;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.*;

import javax.inject.Inject;


import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMRules;
import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;

import org.jboss.byteman.rule.helper.Helper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.orm.hibernate3.HibernateJdbcException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.support.AnnotationConfigContextLoader;
import org.springframework.transaction.CannotCreateTransactionException;

import byteman.demo.config.AppConfig;
import byteman.demo.domain.Customer;
import byteman.demo.services.CustomerService;

import java.util.concurrent.atomic.AtomicInteger;

@RunWith( SpringJUnit4ClassRunner.class )
@ContextConfiguration(loader = AnnotationConfigContextLoader.class, classes = { AppConfig.class } )
@BMScript(value="check.btm")
@BMUnitConfig
public class BytemanDemoTest {
	@Rule public BytemanRule byteman = BytemanRule.create( BytemanDemoTest.class );
	@Inject private CustomerService customerService;

	@Test
	public void testShouldReturnZeroWithoutBytecodeModification() {
		// when
		int result = BytemanBasicDemo.returnZero();

		// then
		assertEquals(0, result);
	}

	@Test
	@BMRule(name = "should return five after bytecode modification",
			targetClass = "BytemanBasicDemo",
			targetMethod = "returnZero",
			targetLocation = "AT EXIT",
			action = "$! = 5")
	public void testShouldReturnFiveAfterBytecodeModification() {
		// when
		int result = BytemanBasicDemo.returnZero();

		// then
		assertEquals(0, result);
	}

	@Test
	public void testSumOfResultShouldEqualOneWithoutBytecodeModification() {

		// when
		int result = BytemanBasicDemo.returnZero() + BytemanBasicDemo.returnOne();

		// then
		assertEquals(1, result);
	}

	@Test
	@BMRules(rules = {
			@BMRule(name = "should return five after bytecode modification",
					targetClass = "BytemanBasicDemo",
					targetMethod = "returnZero",
					targetLocation = "AT EXIT",
					action = "$! = 5"),
			@BMRule(name = "should return four after bytecode modification",
					targetClass = "BytemanBasicDemo",
					targetMethod = "returnOne",
					targetLocation = "AT EXIT",
					action = "$! = 4")})
	public void testSumOfResultShouldEqualNineAfterBytecodeModification() {

		// when
		int result = BytemanBasicDemo.returnZero() + BytemanBasicDemo.returnOne();

		// then
		assertNotEquals(1, result);
	}

	@Test
	@BMRule(name = "should wait until all threads completed",
			targetClass = "BytemanAsyncThreadDemo",
			targetMethod = "run",
			targetLocation = "AT EXIT",
			action = "joinEnlist(\"BytemanDemoTest.shouldWaitUntilAllThreadsCompleted\")")
	public void testShouldWaitUntilAllThreadsCompleted()
	{
		// given
		int expectedCount = 20;
		AtomicInteger atomicInteger = new AtomicInteger(0);
		BMUnitDemoHelper bmUnitDemoHelper = new BMUnitDemoHelper();
		bmUnitDemoHelper.createJoin("BytemanDemoTest.shouldWaitUntilAllThreadsCompleted", expectedCount);

		// when
		for (int i = 0; i < expectedCount; i++)
		{
			new BytemanAsyncThreadDemo(atomicInteger).start();
		}
		BMUnitDemoHelper helper2 = new BMUnitDemoHelper();
		helper2.joinWait("BytemanDemoTest.shouldWaitUntilAllThreadsCompleted", expectedCount);

		// then
		assertEquals(expectedCount, atomicInteger.get());
	}

	@Test()
	@BMRules(
			rules = {
					@BMRule(
							name="create countDown for BytemanAsyncThreadDemo",
							targetClass = "BytemanAsyncThreadDemo",
							targetMethod = "run",
							targetLocation = "AT ENTRY",
							action = "joinEnlist(\"BytemanDemoTest.testMimicOneThreadFailure\"); createCountDown( \"threadCount\", 1 )"
					)
					,
					@BMRule(
							name = "throw IOException when trying to execute 2nd thread for BytemanAsyncThreadDemo",
							targetClass = "BytemanAsyncThreadDemo",
							targetMethod = "run",
							targetLocation = "AT ENTRY",
							condition = "countDown( \"threadCount\" )",
							action = "throw new java.lang.NullPointerException( \"NPE failure\" )"
					)

			}
	)
	public void testMimicOneThreadFailure() {
		// given
		int expectedCount = 1;
		int noOfThreads = 2;
		AtomicInteger atomicInteger = new AtomicInteger(0);
		BMUnitDemoHelper bmUnitDemoHelper = new BMUnitDemoHelper();
		bmUnitDemoHelper.createJoin("BytemanDemoTest.testMimicOneThreadFailure", noOfThreads);

		// when
		for (int i = 0; i < noOfThreads; i++)
		{
			new BytemanAsyncThreadDemo(atomicInteger).start();
		}

		BMUnitDemoHelper helper2 = new BMUnitDemoHelper();
		helper2.joinWait("BytemanDemoTest.testMimicOneThreadFailure", noOfThreads);

		// then
		assertEquals(expectedCount, atomicInteger.get());

	}


	@Test
	public void testCreateCustomerAndVerifyItHasBeenCreated() throws Exception {
		Customer expectedCustomer = customerService.create( "Customer A" );
		Customer actualCustomerReturnedFromService = customerService.find( expectedCustomer.getId() );
		assertThat( actualCustomerReturnedFromService, notNullValue() );
		assertTrue(actualCustomerReturnedFromService.getName().equals(expectedCustomer.getName()));
	}

	@Test( expected = CannotCreateTransactionException.class )
	@BMRules(
		rules = {
			@BMRule(
				name="create countDown for AbstractPlainSocketImpl",
                targetClass = "java.net.AbstractPlainSocketImpl",
                targetMethod = "getOutputStream",
                condition = "$0.port==3306",
                action = "createCountDown( \"connection\", 1 )"
            ),
			@BMRule(
		    	name = "throw IOException when trying to execute 2nd query to MySQL",
		        targetClass = "java.net.AbstractPlainSocketImpl",
		        targetMethod = "getOutputStream",
		        condition = "$0.port==3306 && countDown( \"connection\" )",
		        action = "throw new java.io.IOException( \"Connection refused (simulated)\" )"
		    )
		}
	)
	public void testCreateCustomerAndTryToFindItWhenDatabaseIsDown() {
		Customer customer = customerService.create( "Customer A" );
		customerService.find( customer.getId() );
	}


	@Test(expected = HibernateJdbcException.class)
	@BMRules(
		rules =	@BMRule(
    	name = "introduce timeout while accessing MySQL database",
        targetClass = "com.mysql.cj.jdbc.ClientPreparedStatement",
        targetMethod = "executeQuery",
        targetLocation = "AT ENTRY",
        action = "throw new com.mysql.cj.jdbc.exceptions.MySQLTimeoutException( \"Statement timed out (simulated)\" )"
    ))
	public void testCreateCustomerWhileDatabaseIsTimingOut()  {
		Customer customer = customerService.create( "Customer A" );
		customerService.find( customer.getId() );
	}

}
