# Set the terminal to PNG and specify the output file
set terminal pngcairo enhanced font 'Helvetica Neue,12'
set output 'code-value-search-100k.png'

# Set the data separator and skip the header
set datafile separator "|"
set style data histograms
set style histogram clustered
set style fill solid border -1
set boxwidth 0.8
set key left top reverse

# Define x-axis and y-axis labels
set title "Code Value Search 100k"
set xlabel 'System'
set ylabel 'Patients/s'
set format y "%.0f k"

# Define grid
set grid ytics

# Define line styles and colors for each code
set style line 1 lc rgb '#4DA8DA'
set style line 2 lc rgb '#2E75B6'
set style line 3 lc rgb '#1F4C7A'

# Plot the data
plot 'code-value-search-100k.txt' using 7:xtic(1) every 3 ls 1 title '10 k hits', \
     'code-value-search-100k.txt' using 7:xtic(1) every 3::1 ls 2 title '50 k hits', \
     'code-value-search-100k.txt' using 7:xtic(1) every 3::2 ls 3 title '100 k hits'
