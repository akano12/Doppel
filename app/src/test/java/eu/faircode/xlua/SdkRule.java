package eu.faircode.xlua;

import org.junit.rules.ExternalResource;

// Runs a test as another Android version, which the unit tests cannot get from Build.VERSION.SDK_INT (0 there):
//   @Rule public final SdkRule sdk = new SdkRule();   then   sdk.set(33);
public class SdkRule extends ExternalResource {
    private final int saved = Util.SDK;

    public void set(int sdk) {
        Util.SDK = sdk;
    }

    @Override
    protected void after() {
        Util.SDK = saved;
    }
}
