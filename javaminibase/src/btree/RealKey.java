package btree;

public class RealKey extends KeyClass {

    private Float key;

    public String toString() {
        return key.toString();
    }

    /**
     * Class constructor
     *
     * @param value the value of the real key to be set
     */
    public RealKey(Float value) {
        key = Float.valueOf(value.floatValue());
    }

    /**
     * Class constructor
     *
     * @param value the value of the real key to be set
     */
    public RealKey(float value) {
        key = Float.valueOf(value);
    }

    /**
     * get a copy of the real key
     *
     * @return the reference of the copy
     */
    public Float getKey() {
        return Float.valueOf(key.floatValue());
    }

    /**
     * set the real key value
     *
     * @param value the value to set
     */
    public void setKey(Float value) {
        key = Float.valueOf(value.floatValue());
    }
}