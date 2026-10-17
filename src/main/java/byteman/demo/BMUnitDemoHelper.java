package byteman.demo;

import org.jboss.byteman.rule.Rule;
import org.jboss.byteman.rule.helper.Helper;

public class BMUnitDemoHelper extends Helper {

    protected BMUnitDemoHelper(Rule rule) {
        super(rule);
    }

    BMUnitDemoHelper(){
        super(null);
    }

    @Override
    public String toString() {
        return "BMUnitDemoHelper";
    }
}
