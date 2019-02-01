package edu.pjatk.inn.coffeemaker;

import edu.pjatk.inn.coffeemaker.impl.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sorcer.test.ProjectContext;
import org.sorcer.test.SorcerTestRunner;
import sorcer.service.ContextException;
import sorcer.service.Exertion;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static sorcer.eo.operator.*;
import static sorcer.so.operator.eval;

/**
 * @author Denys Yelchaninov
 */
@RunWith(SorcerTestRunner.class)
@ProjectContext("examples/coffeemaker")
public class FavouriteServiceTest {
    private final static Logger logger = LoggerFactory.getLogger(FavouriteServiceTest.class);

    private MakeOrderImpl service;

    @Before
    public void setUp() throws ContextException {
        service = new MakeOrderImpl();
    }

    @Test
    public void testAddOrder() {
        CoffeeMaker coffeeMaker = favouriteService.getCoffeeMaker();
        Order order = new Order();
        order.setId(6);

        assertTrue(coffeeMaker.addOrder(order));
    }


    @Test
    public void testRemoveOrder() {
        CoffeeMaker coffeeMaker = favouriteService.getCoffeeMaker();

        Order order = new Order();
        order.setId(8);
        coffeeMaker.addOrder(order);

        Recipe recipe = coffeeMaker.getRecipeForName("espresso");
        order.setRecipe(recipe);

        coffeeMaker.addOrder(favourite);
        assertTrue(coffeeMaker.removeOrder(order));
    }

}
