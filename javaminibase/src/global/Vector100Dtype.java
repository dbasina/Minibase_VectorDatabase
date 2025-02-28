package global;

public class Vector100Dtype
{
    public short[] vector;
    public Vector100Dtype()
    {
        vector = new short[100] ;
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
