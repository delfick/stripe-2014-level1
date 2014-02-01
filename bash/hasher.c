#include <time.h>
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <uuid/uuid.h>
#include <openssl/evp.h>

/*
 *
 * Compile with:
 *    gcc miner.c -lcrypto -luuid
 * You made need to install uuid-dev
 *
*/

int main(int argc, char **argv) {
    if (argc < 6) {
        printf("Usage: ./%s <difficulty> <commit_base_length> <done_file> <update_file> <counter_file>", argv[0]);
        exit(1);
    }

    // Init some ints
    int i;
    int j;
    int padLen;
    int difficulty_point;

    // Used to check if files exist
    struct stat buf;

    // More variables
    char null = '\0';
    long attempt = 0l;
    FILE * counter_file;

    // Get difficulty and determine how long it is
    char * difficulty = argv[1];
    int difficulty_length = strlen(difficulty);

    // Get the length of our commit base and turn it into a size_t
    size_t commit_base_length;
    sscanf(argv[2], "%lu", &commit_base_length);

    // Get other things from cli args
    char * done_file = argv[3];
    char * update_file = argv[4];
    char * counter_file_loc = argv[5];

    // Get the base of the commit from stdin
    char * commit_base = malloc(sizeof(char) * (commit_base_length + 1));
    fread(commit_base, 1, commit_base_length, stdin);
    commit_base[commit_base_length] = '\0';

    unsigned char nonce[200];
    unsigned char datalen[10];
    unsigned char gitstart[15];

    // Variables for creating the message digest
    int md_len;
    const EVP_MD *md;
    EVP_MD_CTX * mdctx;
    EVP_MD_CTX * mdctx2;
    unsigned char * hash = OPENSSL_malloc(EVP_MD_size(EVP_sha1()));

    // Variables for creating the sha
    char * next;
    char * sha1 = malloc(sizeof(char) * 41);
    char * sha1_padded = malloc(sizeof(char) * 41);
    static char hex_digits[] = "0123456789abcdef";

    // Variables for our uuid
    uuid_t newUUID;
    char * uuid_string = malloc(sizeof(char) * 41);

    // Lookup table for converting hash to a hexdigest
    char * lookup_table[256];
    for (i = 0; i < 256; i++) {
        next = malloc(sizeof(char) * 2);
        sprintf(next, "%02x", i);
        lookup_table[i] = next;
    }

    // Generate example nonce to get the length of the nonce
    uuid_generate(newUUID);
    uuid_unparse(newUUID, uuid_string);
    sprintf(nonce, "%s %zu", uuid_string, time(0));
    size_t nonce_length = strlen(nonce);

    // Craft the header
    sprintf(datalen, "%zu", commit_base_length + nonce_length + 1);
    sprintf(gitstart, "commit %s", datalen);

    // Start a message digest
    // We copy this and apply new nonces to it in a loop
    mdctx = EVP_MD_CTX_create();
    EVP_DigestInit_ex(mdctx, EVP_sha1(), NULL);
    EVP_DigestUpdate(mdctx, gitstart, strlen(gitstart));
    EVP_DigestUpdate(mdctx, &null, 1);
    EVP_DigestUpdate(mdctx, commit_base, strlen(commit_base));

// Gotos aren't bad... right?
// Keep going back here till we have a sha that is good
retry:
    attempt += 1;

    // Send counts and check done/update files every so often
    if (attempt % 1000000 == 0) {
        if (attempt > 0) {
            counter_file = fopen(counter_file_loc, "w");
            fprintf(counter_file, "%zu\n", attempt);
            fclose(counter_file);
        }
        attempt = 0;

        if (stat(done_file, &buf) == 0 || stat(update_file, &buf) == 0) {
            exit(1);
        }
    }

    // Generate a uuid for the nonce
    uuid_generate(newUUID);
    uuid_unparse(newUUID, uuid_string);
    sprintf(nonce, "%s %zu", uuid_string, time(0));

    // Initialize a new message digest
    mdctx2 = EVP_MD_CTX_create();
    EVP_DigestInit_ex(mdctx2, EVP_sha1(), NULL);

    // Copy the message digest and create our sha
    EVP_MD_CTX_copy_ex(mdctx, mdctx2);
    EVP_DigestUpdate(mdctx2, nonce, nonce_length);
    EVP_DigestFinal(mdctx2, hash, &md_len);

    // Convert the sha data structure to a hex string
    // Each unsigned char is a two character hex string
    for (i = 0; i < md_len; i++) {
        next = lookup_table[hash[i]];
        difficulty_point = i * 2;

        if (difficulty_point < difficulty_length && next[0] > difficulty[difficulty_point]) {
            goto retry;
        }
        sha1[difficulty_point] = next[0];

        if (difficulty_point+1 < difficulty_length && next[1] > difficulty[difficulty_point+1]) {
            goto retry;
        }
        sha1[difficulty_point+1] = next[1];
    }

    // Print the sha and nonce for the bash script
    printf("%s;%s", sha1, nonce);

    // Once last count
    if (attempt > 0) {
        counter_file = fopen(counter_file_loc, "w");
        fprintf(counter_file, "%zu\n", attempt);
        fclose(counter_file);
    }

    // And we're done !
    exit(0);
}
