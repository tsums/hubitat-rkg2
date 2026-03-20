import me.biocomp.hubitat_ci.device.HubitatDeviceSandbox
import spock.lang.Specification

class RingKeypadGen2DriverSpec extends
        Specification
{
    // Creating a sandbox object for device script from file.
    // At this point, script object is not created.
    // Using Hubitat**Device**Sandbox for app scripts.
    HubitatDeviceSandbox sandbox = new HubitatDeviceSandbox(new File("ring-keypad-gen2.groovy"))

    def "Basic validation"() {
        expect:
            // Compile, construct script object, and initialize metadata
            final def script = sandbox.run()

            // // Call method defined in the script
            // script.configure()
    }
}