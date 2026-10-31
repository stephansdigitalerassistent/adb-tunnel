package ch.heuscher.adbtunnel

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class AdbAuthTest {
    /** The public half of a throwaway key from `adb keygen`, so adb's own encoding of it. */
    private val fromAdb = Base64.getDecoder().decode("QAAAABEd248PjASqaNCDmJoSRq6PhRsFuhcIxLmUuHxl4Mog4SrJuNR729v3gOkKGV26XIZRYunzxuwQG7IKKjxdx383JJLB1RNQ+9hsnxLYtnHdq9q7NS4XLtB1cy4Vtij/Pzx91ojttumvK1EpMlNAZU/0GCvwg8kyQA8+WBHQyXe20buVAg6sGaUUelgTCEO5nVfP0We97jXoT8GyE1owCoKPKS91t6M0qQ/FkpwsBXfotO6iJHDd0grQcUvytvz4O/4PCTuXZMfhQY9BVrRxiwUhDWbO2OyV+RmLMQzfzsM+TAlOFnmZYz4yAGlYOWhPXjjCnZ12wiZLjsEnYpT3QYChQW2p3K4e7JNQtP4pLdX9X67jqLIQFt9vDsEawo4xY06rNPiv4gRCFLWFe1D1RKhwgW26rLlj4Pg6rTfwzNirg1ut+mL/3Id/pS1RBjVddHsx2BFNNR/5AB6ecgjl31PWhxbaLaXmR3AkfUozsnWkMhUWlD1gB5eMcViiH3NYqcfpHvR++tFkYCkQUI6Y8pFiLMGP8qmkhTKTLMKQXwyF+RSuJQNHYxY93zjIoZSyk+HoAZOyjjDE4WyP5ppxk404CYiWamoKUV2casLJHbyUAmL90KfQVcgQneHAlGvnrpUjcUpOxFRqKNtvzR5F5r4oQdhHpaJtPbsFTsuyvYOOSptCcQEAAQA=")

    @Test
    fun encodesLikeAdb() {
        val modulus = BigInteger(1, fromAdb.copyOfRange(8, 264).reversedArray())
        val key = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537))) as RSAPublicKey
        assertArrayEquals(fromAdb, AdbAuth.encodeAdbPublicKey(key))
    }
}
