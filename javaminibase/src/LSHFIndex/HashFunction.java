// package LSHFIndex;

// import global.Vector100Dtype;

// import java.util.Random;
// import java.lang.Math.*;

// /** The hash function is of the form
//  * hash(input) = floor ((random.input + shift) / (bin_length))
//  * random.input is dot product (projection) of the input onto a random plane.
//  **/
// public class HashFunction
// {
//     private int shift;
//     private int bin_length;
//     private Vector100Dtype proj_vector;
//     private Random random;


//     public HashFunction(int bin_length)
//     {
//         this.random = new Random();

//         this.bin_length = bin_length;
//         this.shift = random.nextInt(bin_length);

//         // initialize the random vector to project onto
//         this.proj_vector = new Vector100Dtype();
//         int max = 10000;
//         int min = -10000;
//         for (int i = 0; i < 100; i++)
//         {
//             // This is just a trick to generate a random number in range [-10000, 10000]
//             short randomNumber = (short) (random.nextInt(max - min + 1) + min);
//             proj_vector.vector[i] = randomNumber;
//         }
//     }

//     public int hash(Vector100Dtype input)
//     {
//         int dot_product = 0;
//         int hash = 0;
//         for (int i = 0; i < 100; i++)
//         {
//             dot_product += proj_vector.vector[i] * input.vector[i];
//         }

//         int numerator  = dot_product + shift;
//         int denominator = bin_length;
//         hash =  Math.floorDiv(numerator, denominator);
//         return hash;
//     }

// }
