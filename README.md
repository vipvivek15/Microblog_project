This project allows users to create a public, private key. Using this key pair, users can post and retrieve messages from the /messages endpoint. 

Options:

For creation:
java -jar your_jar.jar create:

Creates a user and a mb.ini file on your computer that stores the public and private key

For posting:
java -jar your_jar.jar post message -f file_name:

Post a message to the /messages endpoint and -f to encode a file while posting. 

For listing:
java -jar your_jar.jar list -s starting_id -c count -sa

Retrieves messages from /messages endpoint with the starting id, count. Also allows users to save an attachment. 
