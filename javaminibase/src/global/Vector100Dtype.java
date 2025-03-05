package global;

public class Vector100Dtype
{
    public short[] vector;
    public Vector100Dtype()
    {
        this.vector = new short[100] ;
    }

    public Vector100Dtype(short[] vector)
    {
        if (vector == null || vector.length != 100)
        {
            throw new IllegalArgumentException("The vector must have exactly 100 elements.");
        }
        this.vector = vector;
    }
    public double get_magnitude()
    {
        double sum = 0.0;
        for (short value : vector)
        {
            double square = Math.pow(value, 2);
            sum += square;
        }
        return Math.sqrt(sum);
    }

    public int get_distance(Vector100Dtype vector2)
    {
        short[] comparision_vector = vector2.vector;
        double sum = 0.0;
        int diff = 0;
        for (int i = 0; i < 100; i++)
        {
            diff = vector[i] - comparision_vector[i];
            sum += diff * diff;
        }
        return (int)Math.sqrt(sum);
    }


}
